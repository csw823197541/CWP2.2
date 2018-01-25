package com.cwp.single.cwp.process;

import com.cwp.allvessel.manager.VesselVisit;
import com.cwp.config.CWPCraneDomain;
import com.cwp.config.CWPDefaultValue;
import com.cwp.config.CWPDomain;
import com.cwp.entity.*;
import com.cwp.entity.CWPConfiguration;
import com.cwp.log.CWPLogger;
import com.cwp.log.CWPLoggerFactory;
import com.cwp.single.cwp.cwpvessel.CWPData;
import com.cwp.single.cwp.cwpvessel.CWPVessel;
import com.cwp.single.cwp.dp.*;
import com.cwp.single.cwp.processorder.CWPHatch;
import com.cwp.utils.CalculateUtil;
import com.cwp.utils.LogPrinter;

import java.util.*;

/**
 * Created by csw on 2017/4/19 23:03.
 * Explain:
 */
public class CWPProcess {

    private CWPLogger cwpLogger = CWPLoggerFactory.getCWPLogger();

    private CWPData cwpData;
    private CWPVessel cwpVessel;

    private DPResult dpResult;
    private DP dp;

    public CWPProcess(VesselVisit vesselVisit) {
        cwpData = new CWPData(vesselVisit);
        cwpVessel = new CWPVessel(cwpData);
        dpResult = new DPResult();
        dp = new DP();
    }

    public void processCWP() {
        cwpLogger.logInfo("CWP algorithm is starting...");
        long st = System.currentTimeMillis();

        cwpVessel.initCwpData();

        List<CWPBay> cwpBays = cwpData.getAllBays();
        if (cwpBays.size() <= 0) {
            return;
        }

        //计算每个倍位的总量，初始化数据第一次调用
        initBayTotalWorkTime(cwpBays, cwpData);
        LogPrinter.printBayWorkTime(cwpBays);

        initMethodParameter(cwpBays, cwpData);

        //计算重点倍
        findKeyBay(cwpBays, cwpData);

        //分析舱总量与船期，计算用哪几部桥机、设置桥机开工时间、CWPData全局开始时间
        analyzeVessel(cwpBays, cwpData);

        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        if (cwpCranes.size() <= 0) {
            return;
        }

        //分析桥机信息：故障（可移动、不可移动）、维修计划时间、桥机物理移动范围
        analyzeCrane(cwpCranes, cwpBays, cwpData);

        cwpCranes = CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData);

        //桥机故障在固定倍位不能动
        if (!CraneMethod.analyzeCraneByBreakdownNotMove(cwpCranes, cwpBays, cwpData)) {
            //桥机分块方法
            //有物理移动范围限制
            boolean moveRange = DivideMethod.divideCraneMoveRangeByCranePhysicRange(cwpCranes, cwpBays, cwpData);
            //没有物理移动范围限制
            if (!moveRange) {
                boolean divideByMaxRoad = DivideMethod.divideCraneMoveRangeByMaxRoad(cwpCranes, cwpBays, cwpData);
                if (!divideByMaxRoad) {
                    DivideMethod.divideCraneMoveRange(cwpCranes, cwpBays, cwpData);
                    int maxCraneNum = PublicMethod.getMaxCraneNum(cwpBays, cwpData);
                    List<CWPCrane> cwpCraneList = PublicMethod.getAvailableCraneList(cwpCranes); //非维修桥机
                    if (maxCraneNum == cwpCraneList.size()) { //这个时候不要自动减桥机
                        cwpData.getMethodParameter().setAutoDeleteCrane(false);
                    }
                }
                LogPrinter.printCraneDividedInfo(cwpCranes);
                //桥机分块后，需要分析桥机是否跨驾驶台、烟囱等，现有队列中的有效桥机是否满足船期、分割舱的分割量还需要重新分割
//                if (analyzeAndDivideCraneMoveRangeAgain(cwpCranes, cwpBays, cwpData)) {
//                    LogPrinter.printCraneDividedInfo(cwpCranes);
//                }
            }
        }

        //初始化DPCraneSelectBays这个List
        PublicMethod.initDpCraneSelectBayWorkTime(cwpCranes, cwpBays, cwpData);

        //算法入口，递归方法
        search(1);

        long et = System.currentTimeMillis();
        cwpLogger.logInfo("CWP algorithm finished. The running time of algorithm is " + (et - st) / 1000 + "s");
    }

    private void initBayTotalWorkTime(List<CWPBay> cwpBays, CWPData cwpData) {
        List<Integer> bayNos = cwpData.getVesselVisit().getEffectBayNos();
        for (CWPBay cwpBay : cwpBays) {
            if (bayNos.contains(cwpBay.getBayNo())) {
                cwpBay.setDpTotalWorkTime(0L);
            } else {
                cwpBay.setDpTotalWorkTime(cwpVessel.getTotalWorkTime(cwpBay.getBayNo()));
            }
            cwpBay.setDpCurrentTotalWorkTime(cwpBay.getDpTotalWorkTime());
        }
    }

    private void initMethodParameter(List<CWPBay> cwpBays, CWPData cwpData) {
        long allWorkTime = PublicMethod.getCurTotalWorkTime(cwpBays);
        CWPConfiguration cwpConfiguration = cwpData.getCwpConfiguration();
        MethodParameter methodParameter = cwpData.getMethodParameter();
        if (allWorkTime < 525 * cwpData.getCwpConfiguration().getCraneMeanEfficiency()) {
            methodParameter.setChangeSideCraneWork(false);
        }
        methodParameter.setKeyBay(cwpConfiguration.getKeyBay());
        methodParameter.setDividedBay(cwpConfiguration.getDivideBay());
        methodParameter.setDivideByMaxRoad(cwpConfiguration.getDivideByMaxRoad());
    }

    private void findKeyBay(List<CWPBay> cwpBays, CWPData cwpData) {
        long maxWorkTime = Long.MIN_VALUE;
        List<Integer> keyBayNoList = new ArrayList<>();
        for (int j = 0; j < cwpBays.size(); j++) {
            CWPBay cwpBayJ = cwpBays.get(j);
            int k = j;
            Long tempWorkTime = 0L;
            List<Integer> keyBayNoTempList = new ArrayList<>();
            for (; k < cwpBays.size(); k++) {
                CWPBay cwpBayK = cwpBays.get(k);
                if (CalculateUtil.sub(cwpBayK.getWorkPosition(), cwpBayJ.getWorkPosition()) < 2 * cwpData.getCwpConfiguration().getCraneSafeSpan()) {
                    tempWorkTime += cwpBayK.getDpTotalWorkTime();
                    keyBayNoTempList.add(cwpBayK.getBayNo());
                } else {
                    if (tempWorkTime > maxWorkTime) {
                        maxWorkTime = tempWorkTime;
                        keyBayNoList.clear();
                        keyBayNoList.addAll(keyBayNoTempList);
                    }
                    break;
                }
            }
        }
        for (Integer bayNo : keyBayNoList) {
            CWPBay cwpBay = cwpData.getCWPBayByBayNo(bayNo);
            if (cwpBay.getDpTotalWorkTime() > 0L) {
                cwpBay.setKeyBay(true);
            }
        }
    }

    private void analyzeVessel(List<CWPBay> cwpBays, CWPData cwpData) {
        //船舶最少、最多开路数
        Long totalWorkTime = PublicMethod.getCurTotalWorkTime(cwpBays);
        CWPSchedule cwpSchedule = cwpData.getVesselVisit().getCwpSchedule();
        long planBeginWorkTime = cwpSchedule.getPlanBeginWorkTime().getTime() / 1000;
        Long vesselTime = cwpSchedule.getVesselTime() - 3600;
        int minCraneNum = (int) Math.ceil(totalWorkTime.doubleValue() / (vesselTime.doubleValue()));
        int maxCraneNum = PublicMethod.getMaxCraneNum(cwpBays, cwpData);
        cwpData.setInitMinCraneNum(minCraneNum);
        cwpData.setInitMaxCraneNum(maxCraneNum);
        cwpLogger.logInfo("Minimum number of crane is: " + minCraneNum + ", maximum number of crane is: " + maxCraneNum);
        int craneNum = minCraneNum > maxCraneNum ? maxCraneNum : minCraneNum;
        //根据建议开路数参数确定桥机数，要进行合理性验证
        CWPConfiguration cwpConfiguration = cwpData.getCwpConfiguration();
        if (cwpConfiguration.getCraneAdviceNumber() != null) {
            if (cwpConfiguration.getCraneAdviceNumber() <= maxCraneNum) {
                if (cwpConfiguration.getCraneAdviceNumber() > 0) {
                    craneNum = cwpConfiguration.getCraneAdviceNumber();
                }
            } else {
                craneNum = maxCraneNum;
            }
        }
        //初始化相应数目的桥机
        List<String> craneNoList = new ArrayList<>();
        int n = 0;
        List<CWPCranePool> cwpCranePools = cwpData.getVesselVisit().getAllCWPCranePools();
        for (CWPCranePool cwpCranePool : cwpCranePools) {
            craneNoList.add(cwpCranePool.getCraneNo());
            n++;
            if (n >= craneNum) {
                break;
            }
        }
        Collections.sort(craneNoList);
        for (String craneNo : craneNoList) {
            cwpData.addCWPCrane(cwpData.getVesselVisit().getCWPCraneByCraneNo(craneNo));
        }
        LogPrinter.printSelectedCrane(craneNoList);
        //初始化CWP算法全局时间
        if (cwpData.getDoWorkCwp()) {
            long t = 0;
            for (Long time : cwpData.getVesselVisit().getCraneTimeMap().values()) {
                t = Math.max(t, time);
            }
            long curTime = new Date().getTime() / 1000;
            cwpData.setCwpStartTime(curTime + t);
            cwpData.setCwpCurrentTime(curTime + t);
        }
        if (cwpData.getDoPlanCwp()) {
            cwpData.setCwpStartTime(planBeginWorkTime + 3600);
            cwpData.setCwpCurrentTime(planBeginWorkTime + 3600);
        }
        //TODO:桥机开工时间处理，重排时，要考虑桥机继续做完当前倍位剩余的某些指令的时间
        for (CWPCrane cwpCrane : cwpData.getAllCranes()) {
            cwpCrane.setDpCurrentWorkTime(cwpData.getCwpCurrentTime());
        }
    }

    private void analyzeCrane(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays, CWPData cwpData) {
        //维修计划
        List<CWPCraneMaintainPlan> cwpCraneMaintainPlans = cwpData.getVesselVisit().getAllCwpCraneMaintainPlanList();
        for (CWPCraneMaintainPlan cwpCraneMaintainPlan : cwpCraneMaintainPlans) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo(cwpCraneMaintainPlan.getCraneNo());
            if (cwpCrane != null) {
                long maintainEndTime = cwpCraneMaintainPlan.getMaintainEndTime().getTime() / 1000;
                if (cwpData.getCwpCurrentTime() < maintainEndTime) {
                    cwpCrane.addCWPCraneMaintainPlan(cwpCraneMaintainPlan);
                    //TODO:由于桥机队列中有桥机维修计划，所以要重新计算现有桥机是否满足船期
                    long maintainStartTime = cwpCraneMaintainPlan.getMaintainStartTime().getTime() / 1000;
                    if (cwpData.getCwpCurrentTime() >= maintainStartTime) {//桥机需要立即开始维修
                        //桥机置故障状态
                        cwpCrane.setMaintainNow(true);
                        String craneMoveStatus = cwpCraneMaintainPlan.getCraneMoveStatus() != null ? cwpCraneMaintainPlan.getCraneMoveStatus() : cwpCrane.getCraneMoveStatus();
                        cwpCrane.setCraneMoveStatus(craneMoveStatus);
                        if (CWPCraneDomain.STANDING_Y.equals(craneMoveStatus)) { //可以移动
                            cwpCrane.setDpWorkPositionFrom(CalculateUtil.add(cwpBays.get(cwpBays.size() - 1).getWorkPosition(), 2 * cwpData.getCwpConfiguration().getCraneSafeSpan()));
                            cwpCrane.setDpWorkPositionTo(CalculateUtil.add(cwpBays.get(0).getWorkPosition(), 2 * cwpData.getCwpConfiguration().getCraneSafeSpan()));
                        } else { //TODO:不可以移动
                            if (cwpCrane.getWorkVesselBay() != null) {
                                CWPBay cwpBay = cwpData.getCWPBayByBayNo(Integer.valueOf(cwpCrane.getWorkVesselBay()));
                                if (cwpBay != null) {
                                    cwpCrane.setCraneMoveStatus(CWPCraneDomain.STANDING_N);
                                    cwpCrane.setDpCurrentWorkPosition(cwpBay.getWorkPosition());
                                    cwpCrane.setDpCurrentWorkBayNo(cwpBay.getBayNo());
                                    cwpCrane.setDpWorkPositionFrom(cwpBay.getWorkPosition());
                                    cwpCrane.setDpWorkPositionTo(cwpBay.getWorkPosition());
                                }
                            } else {
                                cwpLogger.logError("桥机(craneNo: " + cwpCrane.getCraneNo() + ")处于维修不可移动状态，但是没有给桥机指定固定不动的倍位！该版本算法目前当作可以移动处理！");
                                cwpCrane.setCraneMoveStatus(CWPCraneDomain.STANDING_Y);
                                cwpCrane.setDpWorkPositionFrom(CalculateUtil.add(cwpBays.get(cwpBays.size() - 1).getWorkPosition(), 2 * cwpData.getCwpConfiguration().getCraneSafeSpan()));
                                cwpCrane.setDpWorkPositionTo(CalculateUtil.add(cwpBays.get(0).getWorkPosition(), 2 * cwpData.getCwpConfiguration().getCraneSafeSpan()));
                            }
                        }
                    }
                }
            }
        }
        //桥机物理移动范围
    }

    private boolean analyzeAndDivideCraneMoveRangeAgain(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays, CWPData cwpData) {
        List<CWPMachine> cwpMachines = cwpData.getAllMachines();
        boolean hasCraneThroughMachine = false;
        int count = 0;
        StringBuilder craneNo = new StringBuilder();
        long crossBarTime;
        if (cwpCranes.size() > 1) {
            for (CWPCrane cwpCrane : cwpCranes) {
                for (CWPMachine cwpMachine : cwpMachines) {
                    double machinePo = cwpMachine.getMachinePosition();
                    if (machinePo > cwpCrane.getDpWorkPositionFrom() && machinePo < cwpCrane.getDpWorkPositionTo()) {
                        for (CWPBay cwpBay : cwpBays) {
                            if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1 &&
                                    cwpBay.getWorkPosition() < machinePo) { //？？？？？？
                                if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                                    hasCraneThroughMachine = true;
                                    count++;
                                    craneNo.append(cwpCrane.getCraneNo()).append(" ");
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            if (hasCraneThroughMachine) {
                crossBarTime = count * cwpData.getCwpConfiguration().getCrossBarTime();
                cwpLogger.logInfo("Because crane(" + craneNo + ") must cross machine, it need to divide crane move range again.");
                if (!PublicMethod.meetVesselTime(cwpCranes, cwpBays, crossBarTime, this.cwpData)) {
                    //TODO:不满足船期
                    cwpLogger.logInfo("The number(" + cwpCranes.size() + ") of crane can not meet ship date, it should add a crane from crane pool.");
                } else {
                    PublicMethod.clearCraneAndBay(cwpCranes, cwpBays);
                    DivideMethod.divideCraneMoveRangeAgain(cwpCranes, cwpBays, crossBarTime, this.cwpData);
                }
            }
        }
        return hasCraneThroughMachine;
    }

    private void search(int depth) {
        cwpLogger.logDebug("第" + depth + "次DP:------------------------------------");

        List<CWPCrane> cwpCranes = CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData);
        List<CWPBay> cwpBays = cwpData.getAllBays();

        LogPrinter.printKeyAndDividedBay(cwpBays);

        //计算当前每个倍位总量和可作业量
        computeCurrentWorkTime(cwpCranes, cwpBays, cwpData);
        LogPrinter.printCurBayWorkTime(cwpBays);

        //控制递归是否结束的方法
        if (finishSearch(depth, cwpCranes, cwpBays))
            return;

        DPResult dpResultLast = dpResult.deepCopy();

        //判断是否有桥机加入作业或进入维修状态，对桥机进行重新分块
        if (cwpData.getCraneCanWorkNow() || cwpData.getCraneCanNotWorkNow()) {
            DivideMethod.divideCraneMoveRange(cwpCranes, cwpBays, cwpData);
            cwpData.setCraneCanWorkNow(false);
            cwpData.setCraneCanNotWorkNow(false);
        }

        //改变桥机作业范围
        changeCraneMoveRange(cwpCranes, cwpData);

        //分析方法控制参数，判断每个方法执行与否
//        analyzeChangeDpWTParameters(cwpData);
        MethodParameter methodParameter = cwpData.getMethodParameter();

        //计算当前时刻多少部桥机作业合适，决定是否减桥机、减几部桥机、从哪边减桥机
        autoDelCraneBeforeCurWork(cwpCranes, cwpBays, dpResultLast, cwpData);
        cwpCranes = CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData);
        LogPrinter.printCraneDividedInfo(cwpCranes);

        List<DPCraneSelectBay> dpCraneSelectBays = cwpData.getDpCraneSelectBays();

        //判断桥机选择倍位时，是否经过驾驶台、烟囱等信息
        PublicMethod.analyzeCraneThroughMachine(dpCraneSelectBays, cwpData);
        //根据桥机是否经过烟囱、驾驶台等，计算每部桥机（在自己的作业范围内）选择每个倍位时的作业时间量
        ChangeDpWTMethod.changeDpWTByCraneThroughMachine(dpCraneSelectBays, cwpData);

        //根据方法控制参数，改变每部桥机选择每个倍位是的作业时间量
        ChangeDpWTMethod.changeDpWTByParameters(methodParameter, dpCraneSelectBays, cwpData);
        ChangeDpWTMethod.changeDpWTByParameters(dpResultLast, methodParameter, dpCraneSelectBays, cwpData);

        //根据桥机状态是否正在经过烟囱、驾驶台等，将桥机选择每个倍位时的作业时间量设置为0，即不作业状态???
        ChangeDpWTMethod.changeDpWTByCraneThroughMachineNow(cwpCranes, dpCraneSelectBays);

        LogPrinter.printChangeToDpInfo("changeToDp之前", cwpCranes, cwpBays, dpCraneSelectBays);
        //按贴现公式改变每部桥机选择每个倍位时的作业时间量
        changeWorkTimeToDynamic(cwpCranes, dpCraneSelectBays, cwpData);
        //根据桥机分块作业范围，计算每部桥机选择每个倍位时的作业时间量
        changeWorkTimeByCraneMoveRange(dpResultLast, dpCraneSelectBays, cwpData);
//        ChangeDpWTMethod.changeDpWTByMachineBothSideNumber(dpCraneSelectBays, cwpData);
        ChangeDpWTMethod.changeDpWTByCranePhysicRange(dpCraneSelectBays, cwpData);
        LogPrinter.printChangeToDpInfo("作业范围限定量", cwpCranes, cwpBays, dpCraneSelectBays);

        //根据桥机作业范围内的作业量是否做完，设置桥机是否可以为等待的状态
        changeCraneWorkDoneState(cwpCranes, cwpBays);

        dpResult = dp.cwpKernel(cwpCranes, cwpBays, dpCraneSelectBays, cwpData);

        //根据DP结果，分析DP当前的选择与上次选择的差异，决定是否进行再次DP
        dpResult = analyzeCurDpResult(dp, dpResult, dpResultLast, cwpCranes, cwpBays, dpCraneSelectBays, cwpData);

        //根据DP结果，计算每部桥机在所选倍位作业的最小时间(分割倍作业时间、桥机维修、加减桥机)，即找出启动倍
        long minWorkTime = obtainMinWorkTime(dpResult, cwpData);

        //根据DP结果，以及桥机最小作业时间，对每部桥机的作业量进行编序
        realWork(dpResult, minWorkTime, cwpData);

        search(depth + 1);

    }

    private void computeCurrentWorkTime(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays, CWPData cwpData) {
        List<DPCraneSelectBay> dpCraneSelectBays = cwpData.getDpCraneSelectBays();
        dpCraneSelectBays.clear();
        PublicMethod.initDpCraneSelectBayWorkTime(cwpCranes, cwpBays, cwpData);
        for (CWPBay cwpBay : cwpBays) {
            cwpBay.setDpCurrentTotalWorkTime(cwpVessel.getTotalWorkTime(cwpBay.getBayNo()));
        }
        List<Integer> bayNos = cwpData.getVesselVisit().getEffectBayNos();
        for (CWPCrane cwpCrane : cwpCranes) {
            cwpCrane.setWorkDone(false);
            for (CWPBay cwpBay : cwpBays) {
                DPPair dpPair = new DPPair<>(cwpCrane.getCraneNo(), cwpBay.getBayNo());
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                if (dpCraneSelectBay != null) {
                    dpCraneSelectBay.setDpDistance(Math.abs(cwpCrane.getDpCurrentWorkPosition() - cwpBay.getWorkPosition()));
                    if (!cwpCrane.isMaintainNow()) {
                        long workTime = cwpVessel.getAvailableWorkTime(cwpBay.getBayNo(), cwpCrane);
                        if (cwpData.getCwpCurrentTime() < cwpData.getCwpStartTime() + cwpData.getCwpConfiguration().getBreakDownCntTime()) {
                            if (bayNos.contains(cwpBay.getBayNo())) {
                                workTime = 0L;
                            }
                        }
                        cwpBay.setDpAvailableWorkTime(workTime);
                        dpCraneSelectBay.setDpWorkTime(workTime);
//                        if (workTime > 0) {
//                            dpCraneSelectBay.setDpWorkTime(cwpBay.getDpCurrentTotalWorkTime());
//                        }

                        //test
                        long workTimeL = 0;
                        long workTimeD = 0;
                        CWPHatch cwpHatch = cwpData.getCWPHatchByHatchId(cwpBay.getHatchId());
                        List<Set> cntSetList = cwpHatch.getAvailableWorkList(cwpBay.getBayNo());
                        for (Set set : cntSetList) {
                            Set<MOContainer> moContainerSet = (Set<MOContainer>) set;
                            for (MOContainer moContainer : moContainerSet) {
                                if (moContainer.getDlType().equals("L")) {
                                    workTimeL += 1;
                                    break;
                                }
                                if (moContainer.getDlType().equals("D")) {
                                    workTimeD += 1;
                                    break;
                                }
                            }
                        }
                        if (workTimeD == 0) {
                            workTimeL = workTimeL * cwpData.getCwpConfiguration().getCraneMeanEfficiency();
                            dpCraneSelectBay.addDpWorkTime(cwpData.getCwpConfiguration().getLoadFirstParam() * workTimeL);
                        }
                    }
                }
            }
        }
    }

    private boolean finishSearch(int depth, List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        boolean isFinish = true;
        boolean availableFinish = true;
        CWPConfiguration cwpConfiguration = cwpData.getCwpConfiguration();
        StringBuilder strBuilder = new StringBuilder("bayNo: ");
        for (CWPBay cwpBay : cwpBays) {
            if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                isFinish = false;
                strBuilder.append(cwpBay.getBayNo()).append(":").append(cwpBay.getDpCurrentTotalWorkTime() / cwpConfiguration.getCraneMeanEfficiency()).append("-").append(cwpBay.getDpAvailableWorkTime() / cwpConfiguration.getCraneMeanEfficiency()).append(" ");
            }
            if (cwpBay.getDpAvailableWorkTime() > 0) {
                availableFinish = false;
            }
        }
        //当所有可作业量做完，解除锁定船箱位
        if (!isFinish && availableFinish) {
            cwpLogger.logDebug("-------------开始安排锁住的锁住预留加载CWP计划--------------");
            Map<String, CWPStowageLockLocation> stowageLockLocationMap = cwpData.getVesselVisit().getStowageLockLocationMap();
            for (CWPStowageLockLocation cwpStowageLockLocation : stowageLockLocationMap.values()) {
                cwpStowageLockLocation.setLockFlag(false);
            }
        }
        int d = 100;
        isFinish = depth > d || isFinish;
        if (isFinish) {
            cwpVessel.generateResult();
            if (depth > d) {
                cwpLogger.logError("CWP算法没有排完所有箱子的计划，请检查倍位(" + strBuilder.toString() + ")是否存在锁定指令不能作业或者锁定预留加载的情况，该版本算法要求将该箱子下面需要作业的所有箱子一起锁住(如装船整槽锁，舱下有锁舱上必须都锁)！");
                cwpLogger.logError("CWP算法没有排完所有箱子的计划，或者检查是否合理的设置了桥机的物理行车限制信息！");
                return true;
            }
        }
        if (cwpCranes.size() == 0) {
            cwpLogger.logError("CWP算法没有排完所有箱子的计划，请检查桥机池中信息是否正确（注意桥机开始/结束作业时间信息）！");
            return true;
        }
        return isFinish;
    }

    private void changeCraneMoveRange(List<CWPCrane> cwpCranes, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getCwpConfiguration();
        for (CWPCrane cwpCrane : cwpCranes) {
            Integer bayNoFrom = cwpCrane.getDpWorkBayNoFrom();
            CWPBay cwpBayFrom = cwpData.getCWPBayByBayNo(bayNoFrom);
            Integer bayNoTo = cwpCrane.getDpWorkBayNoTo();
            CWPBay cwpBayTo = cwpData.getCWPBayByBayNo(bayNoTo);
            if (cwpBayFrom != null && cwpBayTo != null) {
                if (cwpBayFrom.isDividedBay()) {
                    if (cwpCrane.getDpWorkTimeFrom() < cwpConfiguration.getCraneMeanEfficiency()) {
                        //TODO:分割倍处理还需要优化
                        Integer nextBayNo = cwpData.getNextBayNo(bayNoFrom);
                        CWPBay nextCwpBay = cwpData.getCWPBayByBayNo(nextBayNo);
                        if (nextCwpBay.getWorkPosition().compareTo(cwpBayTo.getWorkPosition()) < 1) {
                            if (bayNoFrom % 2 == 0 && cwpBayFrom.getDpCurrentTotalWorkTime() == 0) {
                                if (nextCwpBay.getDpCurrentTotalWorkTime() > 15 * cwpConfiguration.getCraneMeanEfficiency()) {
                                    cwpCrane.setDpWorkBayNoFrom(nextBayNo);
                                    cwpCrane.setDpWorkPositionFrom(nextCwpBay.getWorkPosition());
                                }
                            } else {
                                cwpCrane.setDpWorkBayNoFrom(nextBayNo);
                                cwpCrane.setDpWorkPositionFrom(nextCwpBay.getWorkPosition());
                            }
                        }
                    }
                }
            }
            if (cwpBayTo != null && cwpBayFrom != null) {
                if (cwpBayTo.isDividedBay()) {
                    if (cwpCrane.getDpWorkTimeTo() < cwpConfiguration.getCraneMeanEfficiency() - 30) {
                        Integer frontBayNo = cwpData.getFrontBayNo(bayNoTo);
                        CWPBay frontCwpBay = cwpData.getCWPBayByBayNo(frontBayNo);
                        if (frontCwpBay.getWorkPosition().compareTo(cwpBayFrom.getWorkPosition()) > -1) {
                            if (bayNoTo % 2 == 0 && cwpBayTo.getDpCurrentTotalWorkTime() == 0) {
                                if (frontCwpBay.getDpCurrentTotalWorkTime() > 15 * cwpConfiguration.getCraneMeanEfficiency()) {
                                    cwpCrane.setDpWorkBayNoTo(frontBayNo);
                                    cwpCrane.setDpWorkPositionTo(frontCwpBay.getWorkPosition());
                                }
                            } else {
                                cwpCrane.setDpWorkBayNoTo(frontBayNo);
                                cwpCrane.setDpWorkPositionTo(frontCwpBay.getWorkPosition());
                            }
                        }
                    }
                }
            }
        }
    }

    private void changeWorkTimeToDynamic(List<CWPCrane> cwpCranes, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getCwpConfiguration();
        for (CWPCrane cwpCrane : cwpCranes) {
            List<DPCraneSelectBay> dpCraneSelectBayList = DPCraneSelectBay.getDpCraneSelectBaysByCrane(dpCraneSelectBays, cwpCrane.getCraneNo());
            List<DPCraneSelectBay> dpCraneSelectBayCopyList = new ArrayList<>();
            for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBayList) {
                DPCraneSelectBay dpCraneSelectBayCopy = dpCraneSelectBay.deepCopy();
                dpCraneSelectBayCopyList.add(dpCraneSelectBayCopy);
            }
            for (int j = 0; j < dpCraneSelectBayList.size(); j++) {
                DPCraneSelectBay dpCraneSelectBay = dpCraneSelectBayList.get(j);
                CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
                dpCraneSelectBay.setDpWorkTimeToDpBefore(dpCraneSelectBay.getDpWorkTime());
                long dynamicWorkTime = 2 * dpCraneSelectBay.getDpWorkTime();
                if (dynamicWorkTime > 0) {
                    for (int k = j - 1; k >= 0; k--) {
                        DPCraneSelectBay dpCraneSelectBayCopyK = dpCraneSelectBayCopyList.get(k);
                        CWPBay cwpBayK = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBayCopyK.getDpPair().getSecond());
                        if (CalculateUtil.sub(cwpBay.getWorkPosition(), cwpBayK.getWorkPosition()) < 2 * cwpConfiguration.getCraneSafeSpan()) {
                            dynamicWorkTime += dpCraneSelectBayCopyK.getDpWorkTime();
                        } else {
                            break;
                        }
                    }
                    for (int k = j + 1; k < cwpData.getAllBays().size(); k++) {
                        DPCraneSelectBay dpCraneSelectBayCopyK = dpCraneSelectBayCopyList.get(k);
                        CWPBay cwpBayK = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBayCopyK.getDpPair().getSecond());
                        if (CalculateUtil.sub(cwpBayK.getWorkPosition(), cwpBay.getWorkPosition()) < 2 * cwpConfiguration.getCraneSafeSpan()) {
                            dynamicWorkTime += dpCraneSelectBayCopyK.getDpWorkTime();
                        } else {
                            break;
                        }
                    }
                    dpCraneSelectBay.setDpWorkTime(dynamicWorkTime);
                    dpCraneSelectBay.setDpWorkTimeToDpAfter(dynamicWorkTime);
                }
            }
        }
    }

    private void changeWorkTimeByCraneMoveRange(DPResult dpResultLast, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            if (!cwpCrane.isMaintainNow() && !cwpCrane.isThroughMachineNow()) {
                //TODO:过驾驶台的桥机DP不考虑作业
                if (cwpBay.getDpAvailableWorkTime() > 0) {
                    if (cwpData.getMethodParameter().getKeepMaxRoadBay()) {
                        if (cwpBay.getMaxRoadBay()) {
                            dpCraneSelectBay.addDpWorkTime(cwpData.getCwpConfiguration().getKeepSelectedBayWorkTime());
                            dpCraneSelectBay.setDpWorkTimeToDpAfter(dpCraneSelectBay.getDpWorkTime());
//                            Integer bayNo = PublicMethod.getSelectBayNoInDpResult(cwpCrane.getCraneNo(), dpResultLast);
//                            if (cwpBay.getBayNo().equals(bayNo)) {
//                                dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeepSelectedBayWorkTime());
//                            }
                        }
                    }
                    if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1 &&
                            cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) < 1) {

                    } else {
                        long workTime = dpCraneSelectBay.getDpWorkTime();
                        //TODO:自己范围以外的量都设为1，是否合理？
                        ChangeDpWTMethod.setDpWorkTimeOutOfCraneMoveRange(cwpCrane, cwpBay, dpCraneSelectBay, dpCraneSelectBays, cwpData);
                        //如果桥机移动范围界限正好在大倍位置上，则可以放宽范围到两边的小倍位
                        Integer bayNoFrom = cwpCrane.getDpWorkBayNoFrom();
                        Integer bayNoTo = cwpCrane.getDpWorkBayNoTo();
                        if (bayNoFrom % 2 == 0) {
                            Integer bayNoFromFront = cwpData.getFrontBayNo(bayNoFrom);
                            if (cwpBay.getBayNo().equals(bayNoFromFront)) {
                                dpCraneSelectBay.setDpWorkTime(workTime);
                            }
                        }
                        if (bayNoTo % 2 == 0) {
                            Integer bayNoToNext = cwpData.getNextBayNo(bayNoTo);
                            if (cwpBay.getBayNo().equals(bayNoToNext)) {
                                dpCraneSelectBay.setDpWorkTime(workTime);
                            }
                        }
                    }
                }
            } else {
                dpCraneSelectBay.setDpWorkTime(0L);
            }
        }
    }

    private DPResult analyzeCurDpResult(DP dp, DPResult dpResult, DPResult dpResultLast, List<CWPCrane> cwpCranes, List<CWPBay> cwpBays, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        int curDpCraneNum = dpResult.getDpTraceBack().size();
        if (curDpCraneNum == 0) {
            return dpResult;
        }
        List<CWPCrane> availableCwpCraneList = PublicMethod.getAvailableCraneList(cwpCranes);
        List<CWPCrane> unselectedCraneList = PublicMethod.getUnselectedCranesInDpResult(availableCwpCraneList, dpResult);
        int reducedCraneNum = unselectedCraneList.size(); //排除了维修、故障、正在过驾驶台的桥机
        if (reducedCraneNum > 0) {
            cwpLogger.logInfo("The current DP reduced number of crane is " + reducedCraneNum);
        }
        boolean dpAgain = ChangeDpWTMethod.changeToDpAgainByLastSelectBay(dpResult, dpResultLast, dpCraneSelectBays, cwpData);
        if (dpAgain) {
            cwpLogger.logInfo("Run the dp Again.");
            LogPrinter.printChangeToDpInfo("作业范围限定量", cwpCranes, cwpBays, dpCraneSelectBays);
            dpResult = dp.cwpKernel(cwpCranes, cwpBays, dpCraneSelectBays, cwpData);
        }
        boolean dpAgain1 = ChangeDpWTMethod.changeToDpAgainBySteppingCnt(dpResult, dpResultLast, dpCraneSelectBays, cwpData);
        boolean dpAgain2 = ChangeDpWTMethod.changeToDpAgainByLoadSteppingCnt(dpResult, dpResultLast, dpCraneSelectBays, cwpData);
        if (dpAgain1 || dpAgain2) {
            cwpLogger.logInfo("Run the dp Again.");
            LogPrinter.printChangeToDpInfo("作业范围限定量", cwpCranes, cwpBays, dpCraneSelectBays);
            dpResult = dp.cwpKernel(cwpCranes, cwpBays, dpCraneSelectBays, cwpData);
        }
        return dpResult;
    }

    private void changeCraneWorkDoneState(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        for (CWPCrane cwpCrane : cwpCranes) {
            long maxDpCurTotalWT = PublicMethod.getMaxDpCurTotalWorkTimeInCraneMoveRange(cwpCrane, null, cwpBays);
            if (maxDpCurTotalWT == 0) {
                cwpCrane.setWorkDone(true);
            }
        }
    }

    private Long obtainMinWorkTime(DPResult dpResult, CWPData cwpData) {
        if (dpResult.getDpTraceBack().isEmpty()) {
            return 0L;
        }
        CWPConfiguration cwpConfiguration = cwpData.getCwpConfiguration();
        Long minWorkTime = Long.MAX_VALUE;
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpPair.getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpPair.getSecond());
            long craneMinWorkTime = cwpBay.getDpAvailableWorkTime();
            if (cwpCrane.getDpWorkBayNoFrom().equals(cwpBay.getBayNo())) {
                if (craneMinWorkTime > cwpCrane.getDpWorkTimeFrom() && cwpCrane.getDpWorkTimeFrom() > cwpConfiguration.getCraneMeanEfficiency()) {
                    craneMinWorkTime = cwpCrane.getDpWorkTimeFrom();
                }
            }
            if (cwpCrane.getDpWorkBayNoTo().equals(cwpBay.getBayNo())) {
                if (craneMinWorkTime > cwpCrane.getDpWorkTimeTo() && cwpCrane.getDpWorkTimeTo() > cwpConfiguration.getCraneMeanEfficiency()) {
                    craneMinWorkTime = cwpCrane.getDpWorkTimeTo();
                }
            }
            minWorkTime = Math.min(minWorkTime, craneMinWorkTime);
        }
        minWorkTime = CraneMethod.analyzeCraneThroughMachine(minWorkTime, cwpData);
        minWorkTime = CraneMethod.analyzeCraneMaintainPlan(minWorkTime, cwpData);
        minWorkTime = CraneMethod.analyzeCraneDelOrAdd(minWorkTime, cwpData);
        return minWorkTime;
    }

    private void realWork(DPResult dpResult, long minWorkTime, CWPData cwpData) {
        long maxRealWorkTime = Long.MIN_VALUE;
        long wt = 0L;
        List<DPCraneSelectBay> dpCraneSelectBays = cwpData.getDpCraneSelectBays();
        CWPConfiguration cwpConfiguration = cwpData.getCwpConfiguration();
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpPair.getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpPair.getSecond());
            cwpCrane.setDpCurrentWorkPosition(cwpBay.getWorkPosition());
            cwpCrane.setDpCurrentWorkBayNo(cwpBay.getBayNo());
            long moveTime = 0L;
            DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
            if (dpCraneSelectBay != null) {//it can not be null.
                if (!cwpData.getFirstDoCwp()) {
                    moveTime += (long) (dpCraneSelectBay.getDpDistance() / cwpConfiguration.getCraneSpeed());
                    if (cwpCrane.getWorkVesselBay() == null) {
                        moveTime = 0;
                    }
                    if (dpCraneSelectBay.isTroughMachine()) {
                        moveTime += cwpConfiguration.getCrossBarTime();
                    }
                    cwpCrane.addDpCurrentWorkTime(moveTime);//包含过驾驶台的移动时间
                }
                long realMinWorkTime;
                if (minWorkTime > moveTime) {
                    realMinWorkTime = minWorkTime - moveTime;
                } else {
                    if (!cwpData.getFirstDoCwp() && dpCraneSelectBay.isTroughMachine()) {//桥机置为正在移动状态
                        cwpCrane.setThroughMachineNow(true);
                    }
                    realMinWorkTime = 0L;
                }
                long realWorkTime = cwpVessel.doProcessOrder(cwpCrane, cwpBay, realMinWorkTime);
                cwpCrane.setRealWorkTime(realWorkTime);
                cwpCrane.addDpCurrentWorkTime(realWorkTime);
                if (minWorkTime > moveTime && !cwpData.getFirstDoCwp() && dpCraneSelectBay.isTroughMachine()) {//桥机移过驾驶台后还可以继续作业
                    wt = cwpConfiguration.getCrossBarTime() + realWorkTime - minWorkTime;//最后一关多做的时间
//                    cwpCrane.addDpCurrentWorkTime(-cwpConfiguration.getCrossBarTime());
                }
                maxRealWorkTime = Math.max(maxRealWorkTime, realWorkTime);
                if (cwpCrane.getDpWorkBayNoFrom().equals(cwpBay.getBayNo())) {
                    if (cwpBay.isDividedBay()) {
                        cwpCrane.setDpWorkTimeFrom(cwpCrane.getDpWorkTimeFrom() - realWorkTime);
                    }
                }
                if (cwpCrane.getDpWorkBayNoTo().equals(cwpBay.getBayNo())) {
                    if (cwpBay.isDividedBay()) {
                        cwpCrane.setDpWorkTimeTo(cwpCrane.getDpWorkTimeTo() - realWorkTime);
                    }
                }
            }
        }
        maxRealWorkTime += wt > 0 ? wt : 0;
        boolean isFirstRealWork = !(maxRealWorkTime > 0) && cwpData.getFirstDoCwp();
        cwpData.setFirstDoCwp(isFirstRealWork);
        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        for (CWPCrane cwpCrane : cwpCranes) {
            if (!cwpCrane.isThroughMachineNow()) {//当前所有处于非移动状态的桥机加上相同的最大作业时间
//                cwpCrane.addDpCurrentWorkTime(maxRealWorkTime);//使每部桥机在这次规划中作业相同的时间
//                cwpCrane.setRealWorkTime(maxRealWorkTime - cwpCrane.getRealWorkTime());
                if (PublicMethod.getSelectBayNoInDpResult(cwpCrane.getCraneNo(), dpResult) == null) {
                    cwpCrane.addDpCurrentWorkTime(maxRealWorkTime);
                }
            }
            if (cwpCrane.isThroughMachineNow()) {//看是否可以取消移动状态
                if (Math.abs(cwpData.getCwpCurrentTime() - cwpCrane.getDpCurrentWorkTime()) < cwpConfiguration.getCraneMeanEfficiency()) {
                    cwpCrane.setThroughMachineNow(false);
                }
            }
        }
        long maxCurrentTime = Long.MIN_VALUE;
        boolean isAllCraneThroughMachineNow = true;
        for (CWPCrane cwpCrane : cwpCranes) {
            if (!cwpCrane.isThroughMachineNow()) {
                isAllCraneThroughMachineNow = false;
                maxCurrentTime = Math.max(maxCurrentTime, cwpCrane.getDpCurrentWorkTime());
            }
        }
        if (isAllCraneThroughMachineNow) {
            long minCurrentTime = Long.MAX_VALUE;
            CWPCrane cwpCraneMin = null;
            for (CWPCrane cwpCrane : cwpCranes) {
                if (cwpCrane.getDpCurrentWorkTime() < minCurrentTime) {
                    cwpCraneMin = cwpCrane;
                    minCurrentTime = cwpCrane.getDpCurrentWorkTime();
                }
            }
            if (cwpCraneMin != null) {
                cwpCraneMin.setThroughMachineNow(false);
            }
            cwpData.setCwpCurrentTime(minCurrentTime);
        } else {
            cwpData.setCwpCurrentTime(maxCurrentTime);
        }
        for (CWPCrane cwpCrane : cwpCranes) {
            if (cwpCrane.isThroughMachineNow()) { //最后判断正在过驾驶台的桥机是否可以恢复作业状态
                if (cwpData.getCwpCurrentTime().compareTo(cwpCrane.getDpCurrentWorkTime()) > -1) {
                    cwpCrane.setThroughMachineNow(false);
                }
            }
        }
    }

    private void autoDelCraneBeforeCurWork(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays, DPResult dpResultLast, CWPData cwpData) {
        List<CWPCrane> availableCwpCraneList = PublicMethod.getAvailableCraneList(cwpCranes);
        if (cwpData.getMethodParameter().getAutoDeleteCrane()) {
            //计算当前时刻，剩余作业量最大的是哪一条作业路
            List<CWPBay> maxCwpBayList = AutoDelCraneMethod.getMaxWorkTimeCWPBayList(cwpData.getCwpConfiguration().getCraneSafeSpan(), cwpBays);
            if (maxCwpBayList.size() == 0) {
                cwpLogger.logDebug("The max road is not key bay.");
            } else {
                LogPrinter.printMaxCwpBay(maxCwpBayList);
                List<CWPBay> leftCwpBayList = AutoDelCraneMethod.getSideCwpBayList(CWPDomain.LEFT, cwpBays, maxCwpBayList);
                List<CWPBay> rightCwpBayList = AutoDelCraneMethod.getSideCwpBayList(CWPDomain.RIGHT, cwpBays, maxCwpBayList);
                long maxWorkTime = PublicMethod.getCurTotalWorkTime(maxCwpBayList);
                long leftAllWorkTime = PublicMethod.getCurTotalWorkTime(leftCwpBayList);
                long rightAllWorkTime = PublicMethod.getCurTotalWorkTime(rightCwpBayList);
                //计算上次DP选择哪部桥机作业剩余时间量最大的作业路
                String maxCwpCraneNo = AutoDelCraneMethod.getMaxCwpCraneNoInMaxCwpBayList(dpResultLast, maxCwpBayList);
                if (maxCwpCraneNo != null) {
                    cwpLogger.logDebug("The max road is selected by crane(No:" + maxCwpCraneNo + ") in last DP.");
                    CWPCrane maxCwpCrane = cwpData.getCWPCraneByCraneNo(maxCwpCraneNo);
                    List<CWPCrane> leftCwpCraneList = AutoDelCraneMethod.getSideCwpCraneList(CWPDomain.LEFT, availableCwpCraneList, maxCwpCrane);
                    List<CWPCrane> rightCwpCraneList = AutoDelCraneMethod.getSideCwpCraneList(CWPDomain.RIGHT, availableCwpCraneList, maxCwpCrane);
                    //根据公式计算左右两边是否减桥机、减几部桥机
                    long leftExpectWorkTime = maxWorkTime * leftCwpCraneList.size();
                    long rightExpectWorkTime = maxWorkTime * rightCwpCraneList.size();
                    double leftResidue = (double) (leftExpectWorkTime - leftAllWorkTime) / (double) maxWorkTime;
                    double rightResidue = (double) (rightExpectWorkTime - rightAllWorkTime) / (double) maxWorkTime;
                    cwpLogger.logDebug("The left reduced number of crane is: " + leftResidue);
                    cwpLogger.logDebug("The right reduced number of crane is: " + rightResidue);
                    //对桥机作业范围进行重新分块
//                    List<CWPCrane> curCwpCranes = cwpData.getAllCranes();
                    List<CWPCrane> curCwpCranes = CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData);
                    leftCwpCraneList = AutoDelCraneMethod.getSideCwpCraneList(CWPDomain.LEFT, curCwpCranes, maxCwpCrane);
                    boolean delCraneLeft = false, delCraneRight = false;
                    if (leftResidue >= 1.0 && rightResidue >= 0.0) {
                        delCraneLeft = delCraneFromLeftAndRight(CWPDomain.LEFT, maxCwpCrane, leftCwpCraneList, leftCwpBayList, cwpBays, cwpData);
                    }
                    rightCwpCraneList = AutoDelCraneMethod.getSideCwpCraneList(CWPDomain.RIGHT, curCwpCranes, maxCwpCrane);
                    if (rightResidue >= 1.0 && leftResidue >= 0.0) {
                        delCraneRight = delCraneFromLeftAndRight(CWPDomain.RIGHT, maxCwpCrane, rightCwpCraneList, rightCwpBayList, cwpBays, cwpData);
                    }
                    if (delCraneLeft || delCraneRight) {
                        List<CWPCrane> maxCwpCraneList = new ArrayList<>();
                        maxCwpCraneList.add(maxCwpCrane);
                        //判断一下作业最大倍位量的桥机，是否作业了分割倍，将分割倍位标记置为false，说明该桥机不在帮忙作业分割倍位了
                        AutoDelCraneMethod.analyzeMaxRoadCrane(maxCwpCrane, cwpData);
                        PublicMethod.clearCraneAndBay(maxCwpCraneList, maxCwpBayList);
                        DivideMethod.divideCraneMoveRange(maxCwpCraneList, maxCwpBayList, cwpData);
                        //最大作业路分块后，作业量最大的那条作业路分给一部桥机作业了，那么由于之前分割的原因，旁边的桥机是否还有作业最大量倍位的情况
                        AutoDelCraneMethod.analyzeMaxRoadCraneAndSideCrane(maxCwpCrane, cwpData);
                        for (CWPBay cwpBay : maxCwpBayList) { //发生重新分块时，才设置成true
                            cwpBay.setMaxRoadBay(true);
                        }
                    }
                    //处理一下最后只剩下重点路和旁边一部共两部桥机，并且旁边桥出现作业范围内没有作业倍位的特殊情况
                    if ((leftAllWorkTime == 0 && rightResidue < 0.0) || (rightAllWorkTime == 0 && leftResidue < 0.0)) { //最大量一边有桥机没有作业量，另一边有作业量没有桥机
//                        curCwpCranes = cwpData.getAllCranes();
                        curCwpCranes = CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData);
                        int maxCraneNum = PublicMethod.getMaxCraneNum(cwpBays, cwpData);
                        if (curCwpCranes.size() == maxCraneNum && maxCraneNum == 2) { //正好只有两部桥机，则重新分块
                            computeCurrentWorkTime(curCwpCranes, cwpBays, cwpData);
                            PublicMethod.clearCraneAndBay(curCwpCranes, cwpBays);
                            DivideMethod.divideCraneMoveRange(curCwpCranes, cwpBays, cwpData);
                        }
                    }
                }
            }
        }

        //根据当前时刻最多能放下的桥机数，决定是否减桥机
        int maxCraneNum = PublicMethod.getMaxCraneNum(cwpBays, cwpData);
//        availableCwpCraneList = PublicMethod.getAvailableCraneList(cwpData.getAllCranes());
        availableCwpCraneList = PublicMethod.getAvailableCraneList(CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData));
        int redundantCraneNum = availableCwpCraneList.size() - maxCraneNum;
        List<CWPCrane> unselectedCraneList = PublicMethod.getUnselectedCranesInDpResult(availableCwpCraneList, dpResultLast);
        if (redundantCraneNum > 0) {
//            cwpLogger.logInfo("It need to delete craneNum: " + redundantCraneNum);
//            List<CWPCrane> unselectedCraneList = PublicMethod.getUnselectedCranesInDpResult(availableCwpCraneList, dpResultLast);
            if (redundantCraneNum == unselectedCraneList.size()) {
//                cwpLogger.logInfo("The number of unselected crane is the same as redundant cranes in lastDpResult.");
                boolean reduced = false;
                for (CWPCrane cwpCrane : unselectedCraneList) {
                    if (PublicMethod.isFirstOrLastCrane(cwpCrane.getCraneNo(), cwpData)) {
                        cwpLogger.logDebug("The crane(No:" + cwpCrane.getCraneNo() + ") is deleted properly.");
                        cwpData.removeCWPCrane(cwpCrane);
                        reduced = true;
                    } else {
                        cwpLogger.logDebug("The reduced crane(No:" + cwpCrane.getCraneNo() + ") is not the last or first one.");
                    }
                }
                if (reduced) { //只是减了桥机，并没有进行重新分块
//                    List<CWPCrane> curCwpCranes = cwpData.getAllCranes();
                    List<CWPCrane> curCwpCranes = CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData);
                    computeCurrentWorkTime(curCwpCranes, cwpBays, cwpData);
                }
            } else if (redundantCraneNum > unselectedCraneList.size()) {

            }
        }

        //两边的桥机做完了，把两边的桥机减掉
        unselectedCraneList = PublicMethod.getUnselectedCranesInDpResult(availableCwpCraneList, dpResultLast);
        if (dpResultLast.getDpTraceBack().size() > 0 && unselectedCraneList.size() > 0) {
            boolean reduced = false;
            for (CWPCrane cwpCrane : unselectedCraneList) {
                if (PublicMethod.isFirstOrLastCrane(cwpCrane.getCraneNo(), cwpData)) {
                    long maxDpCurTotalWT = PublicMethod.getMaxDpCurTotalWorkTimeInCraneMoveRange(cwpCrane, null, cwpBays);
                    if (maxDpCurTotalWT < CWPDefaultValue.steppingCntWaitTime) {
                        cwpLogger.logDebug("The crane(No:" + cwpCrane.getCraneNo() + ") is deleted properly.");
                        cwpData.removeCWPCrane(cwpCrane);
                        reduced = true;
                    } else {
                        cwpLogger.logDebug("The reduced crane(No:" + cwpCrane.getCraneNo() + ") need do the remaining amount of work.");
                    }
                } else {
                    cwpLogger.logDebug("The reduced crane(No:" + cwpCrane.getCraneNo() + ") is not the last or first one.");
                }
            }
            if (reduced) { //只是减了桥机，并没有进行重新分块
//                    List<CWPCrane> curCwpCranes = cwpData.getAllCranes();
                List<CWPCrane> curCwpCranes = CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData);
                computeCurrentWorkTime(curCwpCranes, cwpBays, cwpData);
            }
        }

    }

    private boolean delCraneFromLeftAndRight(String side, CWPCrane maxCwpCrane, List<CWPCrane> sideCwpCraneList, List<CWPBay> sideCwpBayList, List<CWPBay> cwpBays, CWPData cwpData) {
        boolean delCrane = false;
        int maxCraneNum = PublicMethod.getMaxCraneNum(sideCwpBayList, cwpData);
        int delCraneNum = sideCwpCraneList.size() - maxCraneNum;
        if (delCraneNum == 0) { //分析每条作业路当前剩余量，看能否一次多减几部桥机
            List<List<CWPBay>> everyRoadBayList = PublicMethod.getEveryRoadBayList(sideCwpBayList, cwpData);
            for (int i = 0; i < everyRoadBayList.size(); i++) {
                List<CWPBay> cwpBayList = everyRoadBayList.get(i);
                if (cwpBayList.size() == 1) {
                    CWPBay cwpBay = cwpBayList.get(0);
                    List<Integer> hatchBayNoList = cwpData.getCWPHatchByHatchId(cwpBay.getHatchId()).getBayNos();
                    if (cwpBay.getBayNo() % 2 == 1 && hatchBayNoList.size() == 3) { //几个垫脚箱单独成一条路的就不要算成一条作业路了
                        if (cwpBay.getDpCurrentTotalWorkTime() < 8 * cwpData.getCwpConfiguration().getCraneMeanEfficiency()) {
                            //TODO:如果不是最后一条或者第一条作业路，说明这个小倍位单独成立的一条路可以算给其它作业路，可以多减一部桥机???
                            if (CWPDomain.LEFT.equals(side) && i != 0) {
                                delCraneNum++;
                            }
                            if (CWPDomain.RIGHT.equals(side) && i != everyRoadBayList.size() - 1) {
                                delCraneNum++;
                            }
//                            for (Integer bayNo : hatchBayNoList) {
//                                if (!bayNo.equals(cwpBay.getBayNo()) && cwpData.getCWPBayByBayNo(bayNo).getDpCurrentTotalWorkTime() > 0) {
//                                    delCraneNum++;
//                                    break;
//                                }
//                            }
                        }
                    }
                }
            }
        }
        boolean notDelCrane = maxCraneNum == 1 && delCraneNum == 1;
        if (notDelCrane) { //当最后只剩下一条作业路时，不要往两边挤桥机
            delCraneNum = 0;
        }
//        cwpLogger.logInfo("Analyze the " + side + " number of crane, maxCraneNum: " + maxCraneNum + ", curCraneNum: " + sideCwpCraneList.size());
        for (int i = 0; i < delCraneNum && i < sideCwpCraneList.size(); i++) {
            int k = side.equals(CWPDomain.LEFT) ? i : sideCwpCraneList.size() - i - 1;
            CWPCrane reducedCwpCrane = sideCwpCraneList.get(k);
            cwpLogger.logDebug("The crane(No:" + reducedCwpCrane.getCraneNo() + ") is deleted properly.");
            cwpData.removeCWPCrane(reducedCwpCrane);
            delCrane = true;
        }
        boolean sideDivide = side.equals(CWPDomain.LEFT) ? cwpData.getLeftDivide() : cwpData.getRightDivide(); //左右两边是否减过桥机，决定当前是否重新分块
        if ((delCrane || sideDivide) && !notDelCrane) {
//            List<CWPCrane> curCwpCranes = cwpData.getAllCranes();
            List<CWPCrane> curCwpCranes = CraneMethod.getAllCranesExceptDelOrAddCrane(cwpData);
            computeCurrentWorkTime(curCwpCranes, cwpBays, cwpData);
            sideCwpCraneList = AutoDelCraneMethod.getSideCwpCraneList(side, curCwpCranes, maxCwpCrane);
            PublicMethod.clearCraneAndBay(sideCwpCraneList, sideCwpBayList);
            DivideMethod.divideCraneMoveRange(sideCwpCraneList, sideCwpBayList, cwpData);
            if (side.equals(CWPDomain.LEFT)) {
                cwpData.setLeftDivide(false);
            } else {
                cwpData.setRightDivide(false);
            }
            delCrane = true;
        }
        return delCrane;
    }
}
