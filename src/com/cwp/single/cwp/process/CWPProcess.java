package com.cwp.single.cwp.process;

import com.cwp.allvessel.manager.VesselVisit;
import com.cwp.config.CWPCraneDomain;
import com.cwp.entity.*;
import com.cwp.entity.CWPConfiguration;
import com.cwp.log.CWPLogger;
import com.cwp.log.CWPLoggerFactory;
import com.cwp.single.cwp.cwpvessel.CWPData;
import com.cwp.single.cwp.cwpvessel.CWPVessel;
import com.cwp.single.cwp.dp.*;
import com.cwp.single.cwp.processorder.CWPHatch;
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
    private List<DPCraneSelectBay> dpCraneSelectBays;
    private CWPConfiguration cwpConfiguration;
    private MethodParameter methodParameter;

    private boolean leftDivide, rightDivide;

    public CWPProcess(VesselVisit vesselVisit) {
        cwpData = new CWPData(vesselVisit);
        cwpConfiguration = vesselVisit.getCwpConfiguration();
        cwpVessel = new CWPVessel(cwpData);
        dpResult = new DPResult();
        dpCraneSelectBays = new ArrayList<>();
        methodParameter = new MethodParameter();
        leftDivide = true;
        rightDivide = true;
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
        initBayTotalWorkTime(cwpBays);
        LogPrinter.printBayWorkTime(cwpBays);

        initMethodParameter(cwpBays, methodParameter, cwpData);

        //计算重点倍
        findKeyBay(cwpBays);

        //分析舱总量与船期，计算用哪几部桥机、设置桥机开工时间、CWPData全局开始时间
        analyzeVessel(cwpBays);

        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        if (cwpCranes.size() <= 0) {
            return;
        }

        //分析桥机信息：故障（可移动、不可移动）、维修计划时间、桥机物理移动范围
        analyzeCrane(cwpCranes, cwpBays);

        //桥机分块方法
        divideCraneMoveRange(cwpCranes, cwpBays);
        LogPrinter.printCraneDividedInfo(cwpCranes);

        //桥机分块后，需要分析桥机是否跨驾驶台、烟囱等，现有队列中的有效桥机是否满足船期、分割舱的分割量还需要重新分割
        if (analyzeAndDivideCraneMoveRangeAgain(cwpCranes, cwpBays))
            LogPrinter.printCraneDividedInfo(cwpCranes);

        //初始化DPCraneSelectBays这个List
        PublicMethod.initDpCraneSelectBayWorkTime(cwpCranes, cwpBays, dpCraneSelectBays);

        //算法入口，递归方法
        search(1);

        long et = System.currentTimeMillis();
        cwpLogger.logInfo("CWP algorithm finished. The running time of algorithm is " + (et - st) / 1000 + "s");
    }

    private void initBayTotalWorkTime(List<CWPBay> cwpBays) {
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

    private void initMethodParameter(List<CWPBay> cwpBays, MethodParameter methodParameter, CWPData cwpData) {
        long allWorkTime = PublicMethod.getCurTotalWorkTime(cwpBays);
        if (allWorkTime < 525 * cwpConfiguration.getCraneMeanEfficiency()) {
            methodParameter.setChangeSideCraneWork(false);
        }
    }

    private void findKeyBay(List<CWPBay> cwpBays) {
        long maxWorkTime = Long.MIN_VALUE;
        List<Integer> keyBayNoList = new ArrayList<>();
        for (int j = 0; j < cwpBays.size(); j++) {
            CWPBay cwpBayJ = cwpBays.get(j);
            int k = j;
            Long tempWorkTime = 0L;
            List<Integer> keyBayNoTempList = new ArrayList<>();
            for (; k < cwpBays.size(); k++) {
                CWPBay cwpBayK = cwpBays.get(k);
                if (cwpBayK.getWorkPosition() - cwpBayJ.getWorkPosition() < 2 * cwpConfiguration.getCraneSafeSpan()) {
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
//        long vesselTime = cwpData.getVesselVisit().getCwpSchedule().getVesselTime();
//        if (maxWorkTime >= vesselTime) {
//            for (Integer bayNo : keyBayNoList) {
//                CWPBay cwpBay = cwpData.getCWPBayByBayNo(bayNo);
//                if (cwpBay.getDpTotalWorkTime() > 0L) {
//                    cwpBay.setKeyBay(true);
//                }
//            }
//        }
        for (Integer bayNo : keyBayNoList) {
            CWPBay cwpBay = cwpData.getCWPBayByBayNo(bayNo);
            if (cwpBay.getDpTotalWorkTime() > 0L) {
                cwpBay.setKeyBay(true);
            }
        }
    }

    private void analyzeVessel(List<CWPBay> cwpBays) {
        //船舶最少、最多开路数
        Long totalWorkTime = PublicMethod.getCurTotalWorkTime(cwpBays);
        CWPSchedule cwpSchedule = cwpData.getVesselVisit().getCwpSchedule();
        long planBeginWorkTime = cwpSchedule.getPlanBeginWorkTime().getTime() / 1000;
        Long vesselTime = cwpSchedule.getVesselTime();
        int minCraneNum = (int) Math.ceil(totalWorkTime.doubleValue() / (vesselTime.doubleValue()));
        int maxCraneNum = PublicMethod.getMaxCraneNum(cwpBays, cwpData);
        cwpData.setInitMinCraneNum(minCraneNum);
        cwpData.setInitMaxCraneNum(maxCraneNum);
        cwpLogger.logInfo("Minimum number of crane is: " + minCraneNum + ", maximum number of crane is: " + maxCraneNum);
        int craneNum = minCraneNum > maxCraneNum ? maxCraneNum : minCraneNum;
        //根据建议开路数参数确定桥机数，要进行合理性验证
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
            if (cwpCranePool.getWorkStartTime() == null || cwpCranePool.getWorkStartTime().getTime() / 1000 <= planBeginWorkTime + 1800) {//去除开工半个小时后加入的桥机
                craneNoList.add(cwpCranePool.getCraneNo());
                n++;
            } else {
                cwpLogger.logInfo("The crane(No:" + cwpCranePool.getCraneNo() + ")'s workStartTime is " + "more than schedule's planBeginWorkTime a half hours, it is del or add crane.");
            }
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
            long curTime = new Date().getTime() / 1000;
            cwpData.setCwpStartTime(curTime);
            cwpData.setCwpCurrentTime(curTime);
        }
        if (cwpData.getDoPlanCwp()) {
            cwpData.setCwpStartTime(planBeginWorkTime);
            cwpData.setCwpCurrentTime(planBeginWorkTime);
        }
        //TODO:桥机开工时间处理，重排时，要考虑桥机继续做完当前倍位剩余的某些指令的时间
        for (CWPCrane cwpCrane : cwpData.getAllCranes()) {
            cwpCrane.setDpCurrentWorkTime(cwpData.getCwpCurrentTime());
        }
    }

    private void analyzeCrane(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
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
                            cwpCrane.setDpWorkPositionFrom(cwpBays.get(cwpBays.size() - 1).getWorkPosition() + 2 * cwpConfiguration.getCraneSafeSpan());
                            cwpCrane.setDpWorkPositionTo(cwpBays.get(0).getWorkPosition() - 2 * cwpConfiguration.getCraneSafeSpan());
                        } else { //TODO:不可以移动
                            CWPBay cwpBay = cwpData.getCWPBayByBayNo(Integer.valueOf(cwpCrane.getWorkVesselBay()));
                            cwpCrane.setCraneMoveStatus(CWPCraneDomain.STANDING_N);
                            cwpCrane.setDpCurrentWorkPosition(cwpBay.getWorkPosition());
                            cwpCrane.setDpCurrentWorkBayNo(cwpBay.getBayNo());
                            cwpCrane.setDpWorkPositionFrom(cwpBay.getWorkPosition());
                            cwpCrane.setDpWorkPositionTo(cwpBay.getWorkPosition());
                        }
                    }
                }
            }
        }
        //桥机物理移动范围
    }

    private void divideCraneMoveRange(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        int maxCraneNum = PublicMethod.getMaxCraneNum(cwpBays, cwpData);
        List<CWPCrane> cwpCraneList = PublicMethod.getAvailableCraneList(cwpCranes); //非维修桥机
        if (maxCraneNum == cwpCraneList.size()) {
            DivideMethod.divideCraneMoveRangeWithMaxCraneNum(cwpCraneList, cwpBays, cwpData);
        } else {
            DivideMethod.divideCraneMoveRangeByCurTotalWorkTime(cwpCraneList, cwpBays, cwpData);
        }
    }

    private boolean analyzeAndDivideCraneMoveRangeAgain(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
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
                crossBarTime = count * cwpConfiguration.getCrossBarTime();
                cwpLogger.logInfo("Because crane(" + craneNo + ") must cross machine, it need to divide crane move range again.");
                if (!PublicMethod.meetVesselTime(cwpCranes, cwpBays, crossBarTime, cwpData)) {
                    //TODO:不满足船期
                    cwpLogger.logInfo("The number(" + cwpCranes.size() + ") of crane can not meet ship date, it should add a crane from crane pool.");
                } else {
                    PublicMethod.clearCraneAndBay(cwpCranes, cwpBays);
                    DivideMethod.divideCraneMoveRangeAgain(cwpCranes, cwpBays, crossBarTime, cwpData);
                }
            }
        }
        return hasCraneThroughMachine;
    }

    private void search(int depth) {
        cwpLogger.logDebug("第" + depth + "次DP:------------------------------------");

        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        List<CWPBay> cwpBays = cwpData.getAllBays();

        LogPrinter.printKeyAndDividedBay(cwpBays);

        //计算当前每个倍位总量和可作业量
        computeCurrentWorkTime(cwpCranes, cwpBays);
        LogPrinter.printCurBayWorkTime(cwpBays);

        //控制递归是否结束的方法
        if (finishSearch(depth, cwpBays))
            return;

        DPResult dpResultLast = dpResult.deepCopy();

        //判断是否有桥机加入作业或进入维修状态，对桥机进行重新分块
        if (cwpData.getCraneCanWorkNow() || cwpData.getCraneCanNotWorkNow()) {
            divideCraneMoveRange(cwpCranes, cwpBays);
        }

        //改变桥机作业范围
        changeCraneMoveRange(cwpCranes);

        //分析方法控制参数，判断每个方法执行与否
//        analyzeChangeDpWTParameters(cwpCranes, cwpBays, methodParameter);

        //计算当前时刻多少部桥机作业合适，决定是否减桥机、减几部桥机、从哪边减桥机
        if (methodParameter.getAutoDeleteCrane()) {
            autoDelCraneBeforeCurWork(dpResultLast, cwpCranes, cwpBays);
            cwpCranes = cwpData.getAllCranes();
        }
        LogPrinter.printCraneDividedInfo(cwpCranes);

        //判断桥机选择倍位时，是否经过驾驶台、烟囱等信息
        PublicMethod.analyzeCraneThroughMachine(cwpData, dpCraneSelectBays);
        //根据桥机是否经过烟囱、驾驶台等，计算每部桥机（在自己的作业范围内）选择每个倍位时的作业时间量
        ChangeDpWTMethod.changeDpWTByCraneThroughMachine(cwpData, dpCraneSelectBays);

        //根据方法控制参数，改变每部桥机选择每个倍位是的作业时间量
        ChangeDpWTMethod.changeDpWTByParameters(methodParameter, cwpData, dpCraneSelectBays);
        ChangeDpWTMethod.changeDpWTByParameters(dpResultLast, methodParameter, cwpData, dpCraneSelectBays);

        //根据桥机状态是否正在经过烟囱、驾驶台等，将桥机选择每个倍位时的作业时间量设置为0，即不作业状态???
        ChangeDpWTMethod.changeDpWTByCraneThroughMachineNow(cwpCranes, dpCraneSelectBays);

        LogPrinter.printChangeToDpInfo("changeToDp之前", cwpCranes, cwpBays, dpCraneSelectBays);
        //按贴现公式改变每部桥机选择每个倍位时的作业时间量
        changeWorkTimeToDynamic(cwpCranes, dpCraneSelectBays);

        //根据桥机分块作业范围，计算每部桥机选择每个倍位时的作业时间量
        changeWorkTimeByCraneMoveRange(dpResultLast, dpCraneSelectBays);
        LogPrinter.printChangeToDpInfo("作业范围限定量", cwpCranes, cwpBays, dpCraneSelectBays);

        //根据桥机作业范围内的作业量是否做完，设置桥机是否可以为等待的状态
        changeCraneWorkDoneState(cwpCranes, cwpBays);

        DP dp = new DP();
        dpResult = dp.cwpKernel(cwpCranes, cwpBays, dpCraneSelectBays);

        //根据DP结果，分析DP少选择桥机的原因（1、是否发生少量垫脚箱避让 2、由于保持在上次选择的倍位作业导致DP在少数桥机选择的结果量更大
        // 3、是否放不下这么多桥机了，需要自动减桥机），是否进行再次DP
        dpResult = analyzeCurDpResult(dp, dpResult, dpResultLast, cwpCranes, cwpBays, dpCraneSelectBays);

        //根据DP结果，计算每部桥机在所选倍位作业的最小时间(分割倍作业时间、桥机维修、加减桥机)，即找出启动倍
        long minWorkTime = obtainMinWorkTime(dpResult);

        //根据DP结果，以及桥机最小作业时间，对每部桥机的作业量进行编序
        realWork(dpResult, minWorkTime);

        search(depth + 1);

    }

    private void computeCurrentWorkTime(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        dpCraneSelectBays.clear();
        PublicMethod.initDpCraneSelectBayWorkTime(cwpCranes, cwpBays, dpCraneSelectBays);
        for (CWPBay cwpBay : cwpBays) {
            cwpBay.setDpCurrentTotalWorkTime(cwpVessel.getTotalWorkTime(cwpBay.getBayNo()));
        }
        List<Integer> bayNos = cwpData.getVesselVisit().getEffectBayNos();
        for (CWPCrane cwpCrane : cwpCranes) {
            for (CWPBay cwpBay : cwpBays) {
                DPPair dpPair = new DPPair<>(cwpCrane.getCraneNo(), cwpBay.getBayNo());
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                if (dpCraneSelectBay != null) {
                    dpCraneSelectBay.setDpDistance(Math.abs(cwpCrane.getDpCurrentWorkPosition() - cwpBay.getWorkPosition()));
                    if (!cwpCrane.isMaintainNow()) {
                        long workTime = cwpVessel.getAvailableWorkTime(cwpBay.getBayNo(), cwpCrane);
                        if (cwpData.getCwpCurrentTime() < cwpData.getCwpStartTime() + cwpConfiguration.getBreakDownCntTime()) {
                            if (bayNos.contains(cwpBay.getBayNo())) {
                                workTime = 0L;
                            }
                        }
                        cwpBay.setDpAvailableWorkTime(workTime);
                        dpCraneSelectBay.setDpWorkTime(workTime);
                    }
                }
            }
        }
    }

    private boolean finishSearch(int depth, List<CWPBay> cwpBays) {
        boolean isFinish = true;
        StringBuilder strBuilder = new StringBuilder("bayNo: ");
        for (CWPBay cwpBay : cwpBays) {
            if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                isFinish = false;
                strBuilder.append(cwpBay.getBayNo()).append(":").append(cwpBay.getDpCurrentTotalWorkTime()).append("-").append(cwpBay.getDpAvailableWorkTime()).append(" ");
            }
        }
        int d = 200;
        isFinish = depth > d || isFinish;
        if (isFinish) {
            cwpVessel.generateResult();
            if (depth > d) {
                cwpLogger.logError("Abnormal end of CWP algorithm(endless loop" + d + "), please check the container info in " + strBuilder.toString());
            }
        }
        return isFinish;
    }

    private void changeCraneMoveRange(List<CWPCrane> cwpCranes) {
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

    private void changeWorkTimeToDynamic(List<CWPCrane> cwpCranes, List<DPCraneSelectBay> dpCraneSelectBays) {
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
                        if (cwpBay.getWorkPosition() - cwpBayK.getWorkPosition() < 2 * cwpConfiguration.getCraneSafeSpan()) {
                            dynamicWorkTime += dpCraneSelectBayCopyK.getDpWorkTime();
                        } else {
                            break;
                        }
                    }
                    for (int k = j + 1; k < cwpData.getAllBays().size(); k++) {
                        DPCraneSelectBay dpCraneSelectBayCopyK = dpCraneSelectBayCopyList.get(k);
                        CWPBay cwpBayK = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBayCopyK.getDpPair().getSecond());
                        if (cwpBayK.getWorkPosition() - cwpBay.getWorkPosition() < 2 * cwpConfiguration.getCraneSafeSpan()) {
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

    private void changeWorkTimeByCraneMoveRange(DPResult dpResultLast, List<DPCraneSelectBay> dpCraneSelectBays) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            if (!cwpCrane.isMaintainNow() && !cwpCrane.isThroughMachineNow()) {
                //TODO:过驾驶台的桥机DP不考虑作业
                if (cwpBay.getDpAvailableWorkTime() > 0) {
                    if (methodParameter.getKeepMaxRoadWork()) {
                        if (cwpBay.getMaxRoadBay()) {
                            dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeepSelectedBayWorkTime());
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
                        setDpWorkTimeOutOfCraneMoveRange(cwpCrane, cwpBay, dpCraneSelectBay, dpCraneSelectBays);
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

    private void setDpWorkTimeOutOfCraneMoveRange(CWPCrane cwpCrane, CWPBay cwpBay, DPCraneSelectBay dpCraneSelectBay, List<DPCraneSelectBay> dpCraneSelectBays) {
        Long hatchId = cwpBay.getHatchId();
        CWPHatch cwpHatch = cwpData.getCWPHatchByHatchId(hatchId);
        List<Integer> bayNos = cwpHatch.getBayNos();
        List<Long> dpWTList = new ArrayList<>();
        for (Integer bayNo : bayNos) {
            DPCraneSelectBay dpCraneSelectBay1 = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, new DPPair<>(cwpCrane.getCraneNo(), bayNo));
            if (dpCraneSelectBay1 != null && dpCraneSelectBay1.getDpWorkTimeToDpAfter() > 0) {
                dpWTList.add(dpCraneSelectBay1.getDpWorkTimeToDpAfter());
            }
        }
        Collections.sort(dpWTList, new Comparator<Long>() {
            @Override
            public int compare(Long o1, Long o2) {
                return o2.compareTo(o1);
            }
        });
        for (int i = 0; i < dpWTList.size(); i++) {
            if (dpWTList.get(i).equals(dpCraneSelectBay.getDpWorkTime())) {
                dpCraneSelectBay.setDpWorkTime(3L - i);
                break;
            }
        }
    }

    private DPResult analyzeCurDpResult(DP dp, DPResult dpResult, DPResult dpResultLast, List<CWPCrane> cwpCranes, List<CWPBay> cwpBays, List<DPCraneSelectBay> dpCraneSelectBays) {
        int curDpCraneNum = dpResult.getDpTraceBack().size();
        if (curDpCraneNum == 0) {
            return dpResult;
        }
        List<CWPCrane> availableCwpCraneList = PublicMethod.getAvailableCraneList(cwpCranes);
        List<CWPCrane> reducedCraneList = PublicMethod.getReducedCranesInDpResult(availableCwpCraneList, dpResult);
        int reducedCraneNum = reducedCraneList.size(); //排除了维修、故障、正在过驾驶台的桥机
        if (reducedCraneNum > 0) {
            cwpLogger.logInfo("The current DP reduced number of crane is " + reducedCraneNum);
        }
        boolean dpAgain = ChangeDpWTMethod.changeDpWTByDpAgain(dpResult, dpResultLast, dpCraneSelectBays, methodParameter, cwpData);
        if (dpAgain) {
            cwpLogger.logInfo("Run the dp Again.");
            LogPrinter.printChangeToDpInfo("作业范围限定量", cwpCranes, cwpBays, dpCraneSelectBays);
            dpResult = dp.cwpKernel(cwpCranes, cwpBays, dpCraneSelectBays);
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

    private Long obtainMinWorkTime(DPResult dpResult) {
        if (dpResult.getDpTraceBack().isEmpty()) {
            return 0L;
        }
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
        return minWorkTime;
    }

    private void realWork(DPResult dpResult, long minWorkTime) {
        long maxRealWorkTime = Long.MIN_VALUE;
        long wt = 0L;
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
                if (minWorkTime > moveTime && !cwpData.getFirstDoCwp() && dpCraneSelectBay.isTroughMachine()) {//桥机移过驾驶台后还可以继续作业
                    wt = cwpConfiguration.getCrossBarTime() + realWorkTime - minWorkTime;//最后一关多做的时间
                    cwpCrane.addDpCurrentWorkTime(-cwpConfiguration.getCrossBarTime());
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
                cwpCrane.addDpCurrentWorkTime(maxRealWorkTime);//使每部桥机在这次规划中作业相同的时间
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
    }

    private void autoDelCraneBeforeCurWork(DPResult dpResultLast, List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        List<CWPCrane> availableCwpCraneList = PublicMethod.getAvailableCraneList(cwpCranes);
        //计算当前时刻，剩余作业量最大的是哪一条作业路
        List<CWPBay> maxCwpBayList = AutoDelCraneMethod.getMaxWorkTimeCWPBayList(cwpConfiguration.getCraneSafeSpan(), cwpBays);
        if (maxCwpBayList.size() == 0) {
            cwpLogger.logInfo("The max road is not key bay.");
            return;
        }
        LogPrinter.printMaxCwpBay(maxCwpBayList);

        List<CWPBay> leftCwpBayList = AutoDelCraneMethod.getLeftCwpBayList(cwpBays, maxCwpBayList);
        List<CWPBay> rightCwpBayList = AutoDelCraneMethod.getRightCwpBayList(cwpBays, maxCwpBayList);
        long maxWorkTime = PublicMethod.getCurTotalWorkTime(maxCwpBayList);
        long leftAllWorkTime = PublicMethod.getCurTotalWorkTime(leftCwpBayList);
        long rightAllWorkTime = PublicMethod.getCurTotalWorkTime(rightCwpBayList);
        //计算上次DP选择哪部桥机作业剩余时间量最大的作业路
        String maxCwpCraneNo = AutoDelCraneMethod.getMaxCwpCraneNoInMaxCwpBayList(dpResultLast, maxCwpBayList);
        if (maxCwpCraneNo != null) {
            cwpLogger.logInfo("The max road is selected by crane(No:" + maxCwpCraneNo + ") in last DP.");
            CWPCrane maxCwpCrane = cwpData.getCWPCraneByCraneNo(maxCwpCraneNo);
            List<CWPCrane> leftCwpCraneList = AutoDelCraneMethod.getLeftCwpCraneList(availableCwpCraneList, maxCwpCrane);
            List<CWPCrane> rightCwpCraneList = AutoDelCraneMethod.getRightCwpCraneList(availableCwpCraneList, maxCwpCrane);
            //根据公式计算左右两边是否减桥机、减几部桥机
            long leftExpectWorkTime = maxWorkTime * leftCwpCraneList.size();
            long rightExpectWorkTime = maxWorkTime * rightCwpCraneList.size();
            double leftResidue = (double) (leftExpectWorkTime - leftAllWorkTime) / (double) maxWorkTime;
            double rightResidue = (double) (rightExpectWorkTime - rightAllWorkTime) / (double) maxWorkTime;
            cwpLogger.logInfo("The left reduced number of crane is: " + leftResidue);
            cwpLogger.logInfo("The right reduced number of crane is: " + rightResidue);
            //对桥机作业范围进行重新分块
            boolean divideCraneMoveRange = false;
            List<CWPCrane> curCwpCranes = cwpData.getAllCranes();
            leftCwpCraneList = AutoDelCraneMethod.getLeftCwpCraneList(curCwpCranes, maxCwpCrane);
            if (leftResidue >= 1.0 && rightResidue >= 0.0) {
                boolean delCrane = delCraneFromLeftAndRight("left", leftResidue, leftCwpCraneList, dpResultLast, leftCwpBayList, cwpBays);
                if (delCrane || leftDivide) {
                    curCwpCranes = cwpData.getAllCranes();
                    leftCwpCraneList = AutoDelCraneMethod.getLeftCwpCraneList(curCwpCranes, maxCwpCrane);
                    PublicMethod.clearCraneAndBay(leftCwpCraneList, leftCwpBayList);
                    divideCraneMoveRange(leftCwpCraneList, leftCwpBayList);
                    divideCraneMoveRange = true;
                    leftDivide = false;
                }
            }
            rightCwpCraneList = AutoDelCraneMethod.getRightCwpCraneList(curCwpCranes, maxCwpCrane);
            if (rightResidue >= 1.0 && leftResidue >= 0.0) {
                boolean delCrane = delCraneFromLeftAndRight("right", rightResidue, rightCwpCraneList, dpResultLast, rightCwpBayList, cwpBays);
                if (delCrane || rightDivide) {
                    curCwpCranes = cwpData.getAllCranes();
                    rightCwpCraneList = AutoDelCraneMethod.getRightCwpCraneList(curCwpCranes, maxCwpCrane);
                    PublicMethod.clearCraneAndBay(rightCwpCraneList, rightCwpBayList);
                    divideCraneMoveRange(rightCwpCraneList, rightCwpBayList);
                    divideCraneMoveRange = true;
                    rightDivide = false;
                }
            }
            if (divideCraneMoveRange) {
                List<CWPCrane> maxCwpCraneList = new ArrayList<>();
                maxCwpCraneList.add(maxCwpCrane);
                //判断一下最大倍位的桥机之前是否需要作业分割倍，将分割倍位标记置为false，说明该桥机不在帮忙作业分割倍位了
                AutoDelCraneMethod.analyzeMaxRoadCrane(maxCwpCrane, cwpData);
                PublicMethod.clearCraneAndBay(maxCwpCraneList, maxCwpBayList);
                divideCraneMoveRange(maxCwpCraneList, maxCwpBayList);
                for (CWPBay cwpBay : maxCwpBayList) { //发生重新分块时，才设置成true
                    cwpBay.setMaxRoadBay(true);
                }
            }
        }
    }

    private boolean delCraneFromLeftAndRight(String side, double sideResidue, List<CWPCrane> sideCwpCraneList, DPResult dpResultLast, List<CWPBay> sideCwpBayList, List<CWPBay> cwpBays) {
        boolean delCrane = false;
        int maxCraneNum = PublicMethod.getMaxCraneNum(sideCwpBayList, cwpData);
        int delCraneNum = sideCwpCraneList.size() - maxCraneNum;
        cwpLogger.logInfo("Analyze the " + side + " number of crane, maxCraneNum: " + maxCraneNum + ", curCraneNum: " + sideCwpCraneList.size());
        for (int i = 0; i < delCraneNum && i < sideCwpCraneList.size(); i++) {
            int k = side.equals("left") ? i : sideCwpCraneList.size() - i - 1;
            CWPCrane reducedCwpCrane = sideCwpCraneList.get(k);
            cwpLogger.logInfo("The crane(No:" + reducedCwpCrane.getCraneNo() + ") is deleted properly.");
            cwpData.removeCWPCrane(reducedCwpCrane);
            List<CWPCrane> curCwpCranes = cwpData.getAllCranes();
            computeCurrentWorkTime(curCwpCranes, cwpBays);
            delCrane = true;
        }
        return delCrane;
    }

}
