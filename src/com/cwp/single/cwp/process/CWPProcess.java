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

    public CWPProcess(VesselVisit vesselVisit) {
        cwpData = new CWPData(vesselVisit);
        cwpConfiguration = vesselVisit.getCwpConfiguration();
        cwpVessel = new CWPVessel(cwpData);
        dpResult = new DPResult();
        dpCraneSelectBays = new ArrayList<>();
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

        //分析舱总量与船期，计算用哪几部桥机、设置桥机开工时间、CWPData全局开始时间
        analyzeVessel(cwpBays);

        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        if (cwpCranes.size() <= 0) {
            return;
        }

        //分析桥机信息：故障（可移动、不可移动）、加减桥机时间、维修计划时间、桥机物理移动范围
        analyzeCrane(cwpCranes, cwpBays);

        //计算重点倍
        findKeyBay(cwpBays);

        //桥机分块方法
        if (!hasCraneBreakdown()) {
            divideCraneMoveRange(cwpCranes, cwpBays);
        }
        LogPrinter.printCraneDividedInfo(cwpCranes);

        //桥机分块后，需要分析桥机是否跨驾驶台、烟囱等，现有队列中的有效桥机是否满足船期、分割舱的分割量还需要重新分割
        analyzeCraneAndDivideMoveRangeAgain(cwpCranes, cwpBays);
        LogPrinter.printCraneDividedInfo(cwpCranes);

        //初始化DPCraneSelectBays这个List
        initDpCraneSelectBayWorkTime(cwpCranes, cwpBays);

        //算法入口，递归方法
        search(1);

        long et = System.currentTimeMillis();
        cwpLogger.logInfo("CWP algorithm finished. The running time of algorithm is " + (et - st) / 1000 + "s");
    }

    private void initBayTotalWorkTime(List<CWPBay> cwpBays) {
        for (CWPBay cwpBay : cwpBays) {
            if (isEffectBay(cwpBay)) {
                cwpBay.setDpTotalWorkTime(0L);
            } else {
                cwpBay.setDpTotalWorkTime(cwpVessel.getTotalWorkTime(cwpBay.getBayNo()));
            }
            cwpBay.setDpCurrentTotalWorkTime(cwpBay.getDpTotalWorkTime());
            cwpData.addTotalWorkTime(cwpBay.getDpTotalWorkTime());
            cwpData.addCurTotalWorkTime(cwpBay.getDpCurrentTotalWorkTime());
        }
    }

    private boolean isEffectBay(CWPBay cwpBay) {
        boolean isEffect = false;
        List<Integer> effectBayNos = cwpData.getVesselVisit().getEffectBayNos();
        for (Integer bayNo : effectBayNos) {
            if (bayNo.equals(cwpBay.getBayNo())) {
                isEffect = true;
                break;
            }
        }
        return isEffect;
    }

    private void analyzeVessel(List<CWPBay> cwpBays) {
        Long totalWorkTime = cwpData.getTotalWorkTime();
        CWPSchedule cwpSchedule = cwpData.getVesselVisit().getCwpSchedule();
        long planBeginWorkTime = cwpSchedule.getPlanBeginWorkTime().getTime() / 1000;
        Long vesselTime = cwpSchedule.getVesselTime();
        int minCraneNum = (int) Math.ceil(totalWorkTime.doubleValue() / (vesselTime.doubleValue()));
        int maxCraneNum = getMaxCraneNum();
        cwpData.setMinCraneNum(minCraneNum);
        cwpData.setMaxCraneNum(maxCraneNum);
        cwpLogger.logInfo("Minimum number of crane is: " + minCraneNum + ", maximum number of crane is: " + maxCraneNum);
        int craneNum = cwpData.getMinCraneNum();
        if (minCraneNum > maxCraneNum) {
            craneNum = maxCraneNum;
        }
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
        Set<String> craneNoSet = new HashSet<>();
        int n = 0;
        List<CWPCranePool> cwpCranePools = cwpData.getVesselVisit().getAllCWPCranePools();
        for (CWPCranePool cwpCranePool : cwpCranePools) {
            if (cwpCranePool.getWorkStartTime() == null ||
                    cwpCranePool.getWorkStartTime().getTime() / 1000 <= planBeginWorkTime + 1800) {//去除开工后加入的桥机
                craneNoSet.add(cwpCranePool.getCraneNo());
                n++;
            } else {
                cwpLogger.logInfo("The crane(No:" + cwpCranePool.getCraneNo() + ")'s workStartTime is " + "more than schedule's planBeginWorkTime a half hours, it is del or add crane.");
            }
            if (n >= craneNum) { //TODO：目前是从第一部桥机开始选取，是否还要去除维修计划的桥机
                break;
            }
        }
        for (String craneNo : craneNoSet) {
            cwpData.addCWPCrane(cwpData.getVesselVisit().getCWPCraneByCraneNo(craneNo));
        }
        LogPrinter.printSelectedCrane(craneNoSet);
        //初始化CWP算法全局时间
        if (cwpData.isDoWorkCwp()) {
            long curTime = new Date().getTime() / 1000;
            cwpData.setCurrentWorkTime(curTime);
            cwpData.setStartWorkTime(curTime);
        } else {
            cwpData.setCurrentWorkTime(planBeginWorkTime);
            cwpData.setStartWorkTime(planBeginWorkTime);
        }
        //TODO:桥机开工时间处理
        for (CWPCrane cwpCrane : cwpData.getAllCranes()) {
            cwpCrane.setDpCurrentWorkTime(cwpData.getCurrentWorkTime());
        }
    }

    private int getMaxCraneNum() {
        int maxCraneNum = 0;
        List<CWPBay> cwpBays = cwpData.getAllBays();
        for (int j = 0; j < cwpBays.size(); ) {
            CWPBay cwpBayJ = cwpBays.get(j);
            if (cwpBayJ.getDpCurrentTotalWorkTime() > 0) {
                int k = j;
                for (; k < cwpBays.size(); k++) {
                    CWPBay cwpBayK = cwpBays.get(k);
                    if (cwpBayK.getWorkPosition() - cwpBayJ.getWorkPosition() >= 2 * cwpConfiguration.getCraneSafeSpan()) {
                        break;
                    }
                }
                j = k;
                maxCraneNum++;
            } else {
                j++;
            }
        }
        return maxCraneNum;
    }

    private void analyzeCrane(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        //故障
//        for (CWPCrane cwpCrane : cwpCranes) {
//            if (CWPCraneDomain.RED.equals(cwpCrane.getCraneStatus())) {
//                cwpCrane.setBreakdown(true);
//                if (CWPCraneDomain.STANDING.equals(cwpCrane.getCraneMoveStatus())) { //可以移动
//                    cwpData.setHasCraneBreakdownCanMove(true);
//                    cwpCrane.setDpWorkPositionFrom(cwpBays.get(cwpBays.size() - 1).getWorkPosition() + 2 * cwpConfiguration.getCraneSafeSpan());
//                    cwpCrane.setDpWorkPositionTo(cwpBays.get(0).getWorkPosition() - 2 * cwpConfiguration.getCraneSafeSpan());
//                } else { //TODO:不可以移动
//                    cwpData.setHasCraneBreakdownCanNotMove(true);
//                }
//            }
//        }
        //加、减桥机
        CWPCraneVesselPool cwpCraneVesselPool = cwpData.getVesselVisit().getCWPCraneVesselPool();
        if (cwpCraneVesselPool.getDelCraneNumber() != null && cwpCraneVesselPool.getDelCraneDate() != null) {
            cwpData.setDelCraneNum(cwpCraneVesselPool.getDelCraneNumber().intValue());
            long delCraneTime = cwpCraneVesselPool.getDelCraneDate().getTime() / 1000;
            cwpData.setDelCraneTime(delCraneTime);
            if (cwpData.getCurrentWorkTime() >= delCraneTime) {
                cwpData.setDelCraneNow(true);
            }
        }
        if (cwpCraneVesselPool.getAddCraneNumber() != null && cwpCraneVesselPool.getAddCraneDate() != null) {
            cwpData.setAddCraneNum(cwpCraneVesselPool.getAddCraneNumber().intValue());
            long addCraneTime = cwpCraneVesselPool.getAddCraneDate().getTime() / 1000;
            cwpData.setAddCraneTime(addCraneTime);
            if (cwpData.getCurrentWorkTime() >= addCraneTime) {
                cwpData.setDelCraneNow(true);
            }
        }
        //维修计划
        List<CWPCraneMaintainPlan> cwpCraneMaintainPlans = cwpData.getVesselVisit().getAllCwpCraneMaintainPlanList();
        for (CWPCraneMaintainPlan cwpCraneMaintainPlan : cwpCraneMaintainPlans) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo(cwpCraneMaintainPlan.getCraneNo());
            if (cwpCrane != null) {
                long maintainEndTime = cwpCraneMaintainPlan.getMaintainEndTime().getTime() / 1000;
                if (cwpData.getCurrentWorkTime() < maintainEndTime) {
                    cwpCrane.addCWPCraneMaintainPlan(cwpCraneMaintainPlan);
                    //TODO:由于桥机队列中有桥机维修计划，所以要重新计算现有桥机是否满足船期
                    long maintainStartTime = cwpCraneMaintainPlan.getMaintainStartTime().getTime() / 1000;
                    if (cwpData.getCurrentWorkTime() >= maintainStartTime) {//桥机需要立即开始维修
                        cwpData.setHasCraneCanNotWorkNow(true);
                        cwpCrane.setMaintainNow(true);
                        //桥机置故障状态
                        cwpCrane.setBreakdown(true);
                        String craneMoveStatus = cwpCraneMaintainPlan.getCraneMoveStatus() != null ? cwpCraneMaintainPlan.getCraneMoveStatus() : cwpCrane.getCraneMoveStatus();
                        if (CWPCraneDomain.STANDING.equals(craneMoveStatus)) { //可以移动
                            cwpData.setHasCraneBreakdownCanMove(true);
                            cwpCrane.setDpWorkPositionFrom(cwpBays.get(cwpBays.size() - 1).getWorkPosition() + 2 * cwpConfiguration.getCraneSafeSpan());
                            cwpCrane.setDpWorkPositionTo(cwpBays.get(0).getWorkPosition() - 2 * cwpConfiguration.getCraneSafeSpan());
                        } else { //TODO:不可以移动
                            cwpData.setHasCraneBreakdownCanNotMove(true);
                        }
                    }
                }
            }
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
//                double hatchLengthK = cwpData.getVesselVisit().getMOVessel().getMOHatchByHatchId(cwpBayK.getHatchId()).getHatchLength();
//                tempWorkTime += cwpBayK.getDpTotalWorkTime();
//                keyBayNoTempList.add(cwpBayK.getBayNo());
//                if (cwpBayK.getWorkPosition() - cwpBayJ.getWorkPosition() >= 2 * cwpConfiguration.getCraneSafeSpan() - hatchLengthK * 1 / 4) {
//                    if (tempWorkTime > maxWorkTime) {
//                        maxWorkTime = tempWorkTime;
//                        keyBayNoList.clear();
//                        keyBayNoList.addAll(keyBayNoTempList);
//                    }
//                    break;
//                }
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

    private boolean hasCraneBreakdown() {
        boolean hasCraneBreakdown = false;
        if (cwpData.isHasCraneBreakdownCanMove()) {
            hasCraneBreakdown = true;
            List<CWPCrane> cwpCraneList = new ArrayList<>();
            for (CWPCrane cwpCrane : cwpData.getAllCranes()) {
                if (!cwpCrane.isBreakdown()) {
                    cwpCraneList.add(cwpCrane);
                }
            }
            divideCraneMoveRange(cwpCraneList, cwpData.getAllBays());
            cwpData.setHasCraneBreakdownCanMove(false);//表示这时候的故障桥机算法已考虑???
        } else if (cwpData.isHasCraneBreakdownCanNotMove()) {
            hasCraneBreakdown = true;
        }
        return hasCraneBreakdown;
    }

    private void divideCraneMoveRange(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        int maxCraneNum = getMaxCraneNum();
        cwpData.setMaxCraneNum(maxCraneNum);
        List<CWPCrane> cwpCraneList = new ArrayList<>();
        for (CWPCrane cwpCrane : cwpCranes) {
            if (!cwpCrane.isBreakdown() && !cwpCrane.isMaintainNow()) { //非维修、非故障桥机
                cwpCraneList.add(cwpCrane);
            }
        }
        if (cwpData.getMaxCraneNum() == cwpCraneList.size()) {
            divideCraneMoveRangeWithMaxCraneNum(cwpCraneList, cwpBays);
        } else {
            divideCraneMoveRangeByCurTotalWorkTime(cwpCraneList, cwpBays);
        }
    }

    private void divideCraneMoveRangeWithMaxCraneNum(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        int craneNum = cwpCranes.size();
        int bayNum = cwpBays.size();
        long allWorkTime = cwpData.getCurTotalWorkTime();
        if (craneNum <= 0 || bayNum <= 0 || allWorkTime == 0) {
            return;
        }
        cwpLogger.logInfo("Divide crane move range with maximum number of crane. All workTime: " + allWorkTime + "(" + allWorkTime / cwpConfiguration.getCraneMeanEfficiency() + ")");
        int c = 0;
        for (int j = 0; j < bayNum; ) {
            c = c == craneNum ? craneNum - 1 : c;
            CWPBay cwpBayJ = cwpBays.get(j);
            CWPCrane cwpCrane = cwpCranes.get(c);
            if (cwpBayJ.getDpCurrentTotalWorkTime() > 0) {
                cwpCrane.setDpWorkBayNoFrom(cwpBayJ.getBayNo());
                cwpCrane.setDpWorkPositionFrom(cwpBayJ.getWorkPosition());
                int k = j;
                for (; k < cwpBays.size(); k++) {
                    CWPBay cwpBayK = cwpBays.get(k);
                    if (cwpBayK.getWorkPosition() - cwpBayJ.getWorkPosition() >= 2 * cwpConfiguration.getCraneSafeSpan()) {
                        break;
                    } else {
                        cwpCrane.setDpWorkBayNoTo(cwpBayK.getBayNo());
                        cwpCrane.setDpWorkPositionTo(cwpBayK.getWorkPosition());
                    }
                }
                j = k;
                c++;
            } else {
                j++;
            }
        }
    }

    private void divideCraneMoveRangeByCurTotalWorkTime(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        int craneNum = cwpCranes.size();
        int bayNum = cwpBays.size();
        long allWorkTime = cwpData.getCurTotalWorkTime();
        if (craneNum <= 0 || bayNum <= 0 || allWorkTime == 0) {
            return;
        }
        int realBayNum = 0;
        for (CWPBay cwpBay : cwpBays) {
            realBayNum = cwpBay.getDpCurrentTotalWorkTime() > 0 ? realBayNum + 1 : realBayNum;
        }
        long mean = realBayNum < craneNum ? allWorkTime / realBayNum : allWorkTime / craneNum;
        long meanLittleOrMore = 0L;
        cwpLogger.logInfo("Divide crane move range, all workTime：" + allWorkTime + "(" + allWorkTime / cwpConfiguration.getCraneMeanEfficiency() + "), mean workTime：" + mean);
        int c = 0;
        int cSize = 0;
        long tmpWorkTime = 0L;
        long amount = cwpConfiguration.getAmount() * cwpConfiguration.getCraneMeanEfficiency();
        amount = mean <= amount ? 0 : amount;
        long meanL = mean - amount + 10;
        long meanR = mean + amount - 10;
        for (int j = 0; j < bayNum; j++) {
            CWPBay cwpBay = cwpBays.get(j);
            cSize += 1;
            tmpWorkTime += cwpBay.getDpCurrentTotalWorkTime();
            c = c == craneNum ? craneNum - 1 : c;
            CWPCrane cwpCrane = cwpCranes.get(c);
            if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                cwpCrane.setDpWorkBayNoTo(cwpBay.getBayNo());
                cwpCrane.setDpWorkPositionTo(cwpBay.getWorkPosition());
            }
            if (tmpWorkTime >= meanL && tmpWorkTime <= meanR) {
                cwpCrane.setDpWorkBayNoFrom(cwpBays.get(j + 1 - cSize).getBayNo());
                cwpCrane.setDpWorkPositionFrom(cwpBays.get(j + 1 - cSize).getWorkPosition());
                if (cwpBay.getDpCurrentTotalWorkTime() == 0 && cSize > 0) {
                    cwpCrane.setDpWorkBayNoTo(cwpBay.getBayNo());
                    cwpCrane.setDpWorkPositionTo(cwpBay.getWorkPosition());
                }
                meanLittleOrMore = (tmpWorkTime - mean) / craneNum - c - 1;
                tmpWorkTime = 0L;
                cSize = 0;
                c++;
            } else if (tmpWorkTime > meanR) {
                cwpCrane.setDpWorkBayNoFrom(cwpBays.get(j + 1 - cSize).getBayNo());
                cwpCrane.setDpWorkPositionFrom(cwpBays.get(j + 1 - cSize).getWorkPosition());
                if (cwpBay.getDpCurrentTotalWorkTime() == 0 && cSize > 0) {
                    cwpCrane.setDpWorkBayNoTo(cwpBay.getBayNo());
                    cwpCrane.setDpWorkPositionTo(cwpBay.getWorkPosition());
                }
                mean += 0L - meanLittleOrMore;
                if (c < craneNum - 1) {
                    cwpCrane.setDpWorkTimeTo(cwpBay.getDpCurrentTotalWorkTime() - (tmpWorkTime - mean));
                }
                tmpWorkTime = tmpWorkTime - mean;
                c++;
                if (c < craneNum) {
                    CWPCrane cwpCraneNext = cwpCranes.get(c);
                    cwpCraneNext.setDpWorkTimeFrom(tmpWorkTime);
                    cwpBay.setDividedBay(true);
                }
                cSize = 1;
            } else {
                if (c == craneNum - 1 && j == bayNum - 1) {
                    //解决最后一部桥机没有DpWorkBayNoFrom、DpWorkBayNoTo的问题
                    if (cwpCrane.getDpWorkBayNoFrom() == null) {
                        cwpCrane.setDpWorkBayNoFrom(cwpBays.get(j + 1 - cSize).getBayNo());
                        cwpCrane.setDpWorkPositionFrom(cwpBays.get(j + 1 - cSize).getWorkPosition());
                    }
                    if (cwpCrane.getDpWorkBayNoTo() == null) {
                        Integer nextBayNo = cwpData.getNextBayNo(cwpBays.get(j + 1 - cSize).getBayNo());
                        CWPBay nextBay = cwpData.getCWPBayByBayNo(nextBayNo);
                        cwpCrane.setDpWorkBayNoTo(nextBay.getBayNo());
                        cwpCrane.setDpWorkPositionTo(nextBay.getWorkPosition());
                    }
                }
            }
        }
    }

    private void divideCraneMoveRangeAgain(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays, long additionalTime) {
        int craneNum = cwpCranes.size();
        int bayNum = cwpBays.size();
        if (craneNum <= 0 || bayNum <= 0 || cwpData.getCurTotalWorkTime() == 0) {
            return;
        }
        long allWorkTime = cwpData.getCurTotalWorkTime() + additionalTime;
        int realBayNum = 0;
        for (CWPBay cwpBay : cwpBays) {
            realBayNum = cwpBay.getDpCurrentTotalWorkTime() > 0 ? realBayNum + 1 : realBayNum;
        }
        long mean = realBayNum < craneNum ? allWorkTime / realBayNum : allWorkTime / craneNum;
        long meanLittleOrMore = 0L;
        cwpLogger.logInfo("Divide crane move range again (crane cross machine), all workTime：" + allWorkTime + "(" + allWorkTime / cwpConfiguration.getCraneMeanEfficiency() + "), mean workTime：" + mean);
        int c = 0;
        int cSize = 0;
        long tmpWorkTime = 0L;
        long amount = cwpConfiguration.getAmount() * cwpConfiguration.getCraneMeanEfficiency();
        amount = mean <= amount ? 0 : amount;
        long meanL = mean - amount + 10;
        long meanR = mean + amount - 10;
        for (int j = 0; j < bayNum; j++) {
            CWPBay cwpBay = cwpBays.get(j);
            cSize += 1;
            tmpWorkTime += cwpBay.getDpCurrentTotalWorkTime();
            c = c == craneNum ? craneNum - 1 : c;
            CWPCrane cwpCrane = cwpCranes.get(c);
            if (j == 0) {
                cwpCrane.setDpWorkBayNoFrom(cwpBay.getBayNo());
                cwpCrane.setDpWorkPositionFrom(cwpBay.getWorkPosition());
            }
            if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                cwpCrane.setDpWorkBayNoTo(cwpBay.getBayNo());
                cwpCrane.setDpWorkPositionTo(cwpBay.getWorkPosition());
                if (isCraneThroughMachineInMoveRange(cwpCrane, cwpBay)) {
                    tmpWorkTime += cwpConfiguration.getCrossBarTime();
                }
                if (cwpCrane.getDpWorkBayNoFrom() == null) {
                    cwpCrane.setDpWorkBayNoFrom(cwpBay.getBayNo());
                    cwpCrane.setDpWorkPositionFrom(cwpBay.getWorkPosition());
                }
            }
            if (tmpWorkTime >= meanL && tmpWorkTime <= meanR) {
                if (cwpBay.getDpCurrentTotalWorkTime() == 0 && cSize > 0) {
                    cwpCrane.setDpWorkBayNoTo(cwpBay.getBayNo());
                    cwpCrane.setDpWorkPositionTo(cwpBay.getWorkPosition());
                }
                cwpCrane.setDpWorkPositionFrom(cwpBays.get(j + 1 - cSize).getWorkPosition());
                cwpCrane.setDpWorkBayNoFrom(cwpBays.get(j + 1 - cSize).getBayNo());
                meanLittleOrMore = (tmpWorkTime - mean) / craneNum - c - 1;
                tmpWorkTime = 0L;
                cSize = 0;
                c++;
                if (c < craneNum) {
                    CWPCrane cwpCraneNext = cwpCranes.get(c);
                    cwpCraneNext.setDpWorkBayNoFrom(cwpBay.getBayNo());
                    cwpCraneNext.setDpWorkPositionFrom(cwpBay.getWorkPosition());
                }
            } else if (tmpWorkTime > meanR) {
                if (cwpBay.getDpCurrentTotalWorkTime() == 0 && cSize > 0) {
                    cwpCrane.setDpWorkBayNoTo(cwpBay.getBayNo());
                    cwpCrane.setDpWorkPositionTo(cwpBay.getWorkPosition());
                }
                cwpCrane.setDpWorkPositionFrom(cwpBays.get(j + 1 - cSize).getWorkPosition());
                cwpCrane.setDpWorkBayNoFrom(cwpBays.get(j + 1 - cSize).getBayNo());
                mean += 0L - meanLittleOrMore;
                if (c < craneNum - 1) {
                    cwpCrane.setDpWorkTimeTo(cwpBay.getDpCurrentTotalWorkTime() - (tmpWorkTime - mean));
                }
                tmpWorkTime = tmpWorkTime - mean;
                c++;
                if (c < craneNum) {
                    CWPCrane cwpCraneNext = cwpCranes.get(c);
                    cwpCraneNext.setDpWorkTimeFrom(tmpWorkTime);
                    cwpBay.setDividedBay(true);
                    cwpCraneNext.setDpWorkBayNoFrom(cwpBay.getBayNo());
                    cwpCraneNext.setDpWorkPositionFrom(cwpBay.getWorkPosition());
                }
                cSize = 1;
            } else {
                if (c == craneNum - 1 && j == bayNum - 1) {
                    //解决最后一部桥机没有DpWorkBayNoFrom、DpWorkBayNoTo的问题
                    if (cwpCrane.getDpWorkBayNoFrom() == null) {
                        cwpCrane.setDpWorkBayNoFrom(cwpBays.get(j + 1 - cSize).getBayNo());
                        cwpCrane.setDpWorkPositionFrom(cwpBays.get(j + 1 - cSize).getWorkPosition());
                    }
                    if (cwpCrane.getDpWorkBayNoTo() == null) {
                        CWPBay nextBay = cwpData.getCWPBayByBayNo(cwpData.getNextBayNo(cwpBays.get(j + 1 - cSize).getBayNo()));
                        cwpCrane.setDpWorkBayNoTo(nextBay.getBayNo());
                        cwpCrane.setDpWorkPositionTo(nextBay.getWorkPosition());
                    }
                }
            }
        }
    }

    private boolean isCraneThroughMachineInMoveRange(CWPCrane cwpCrane, CWPBay cwpBay) {
        boolean isThroughMachine = false;
        for (CWPMachine cwpMachine : cwpData.getAllMachines()) {
            double machinePo = cwpMachine.getMachinePosition();
            if (machinePo > cwpCrane.getDpWorkPositionFrom() && machinePo < cwpBay.getWorkPosition()) {
                isThroughMachine = true;
            }
        }
        return isThroughMachine;
    }

    private void analyzeCraneAndDivideMoveRangeAgain(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
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
                if (!whetherMeetVesselTime(cwpCranes, crossBarTime)) {
                    //TODO:不满足船期
                    cwpLogger.logInfo("The number(" + cwpCranes.size() + ") of crane can not meet ship date, it should add a crane from crane pool.");
                } else {
                    clearData(cwpCranes, cwpBays);
                    divideCraneMoveRangeAgain(cwpCranes, cwpBays, crossBarTime);
                }
            }
        }
    }

    private boolean whetherMeetVesselTime(List<CWPCrane> cwpCranes, Long additionalTime) {
        int effectiveCraneNum = 0;//有效的桥机数目
        for (CWPCrane cwpCrane : cwpCranes) {
            if (!cwpCrane.isBreakdown() && !cwpCrane.isMaintainNow()) {
                effectiveCraneNum++;
            }
        }
        long totalWorkTime = cwpData.getCurTotalWorkTime() + additionalTime;
        CWPSchedule cwpSchedule = cwpData.getVesselVisit().getCwpSchedule();
        long vesselTime = cwpSchedule.getVesselTime();
        long workTime = effectiveCraneNum * vesselTime;
        return totalWorkTime < workTime;
    }

    private void clearData(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        for (CWPBay cwpBay : cwpBays) {
            cwpBay.setDividedBay(false);
        }
        for (CWPCrane cwpCrane : cwpCranes) {
            cwpCrane.setDpWorkBayNoFrom(null);
            cwpCrane.setDpWorkBayNoTo(null);
            cwpCrane.setDpWorkTimeFrom(0L);
            cwpCrane.setDpWorkTimeTo(0L);
            if (!cwpCrane.isBreakdown() && !cwpCrane.isMaintainNow()) {
                cwpCrane.setDpWorkPositionFrom(null);
                cwpCrane.setDpWorkPositionTo(null);
            }
            if (cwpCrane.isBreakdown() || cwpCrane.isMaintainNow()) {
                cwpCrane.setDpWorkPositionFrom(cwpBays.get(cwpBays.size() - 1).getWorkPosition() + 2 * cwpConfiguration.getCraneSafeSpan());
                cwpCrane.setDpWorkPositionTo(cwpBays.get(0).getWorkPosition() - 2 * cwpConfiguration.getCraneSafeSpan());
            }
        }
        dpCraneSelectBays.clear();
    }

    private void initDpCraneSelectBayWorkTime(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        for (CWPCrane cwpCrane : cwpCranes) {
            for (CWPBay cwpBay : cwpBays) {
                dpCraneSelectBays.add(new DPCraneSelectBay(new DPPair<>(cwpCrane.getCraneNo(), cwpBay.getBayNo())));
            }
        }
    }

    private void search(int depth) {
        cwpLogger.logDebug("第" + depth + "次DP:------------------------------------");

        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        List<CWPBay> cwpBays = cwpData.getAllBays();

        LogPrinter.printKeyAndDividedBay(cwpBays);

        //计算当前每个倍位总量和可作业量
        if (!isDelOrAddCraneNow() && !hasCraneWorkOrNotWorkNow()) {
            computeCurrentWorkTime(cwpCranes, cwpBays);
        }
        LogPrinter.printCurBayWorkTime(cwpBays);

        boolean isFinish = true;
        StringBuilder strBuilder = new StringBuilder("bayNo: ");
        for (CWPBay cwpBay : cwpBays) {
            if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                isFinish = false;
                strBuilder.append(cwpBay.getBayNo()).append(":").append(cwpBay.getDpCurrentTotalWorkTime()).append("-").append(cwpBay.getDpAvailableWorkTime()).append(" ");
            }
        }
        int d = 100;
        isFinish = depth > d || isFinish;
        if (isFinish) {
            cwpVessel.generateResult();
            if (depth > d) {
                cwpLogger.logError("Abnormal end of CWP algorithm(endless loop" + d + "), please check the container info in " + strBuilder.toString());
            }
            return;
        }

        DPResult dpResultLast = dpResult.deepCopy();

        //改变桥机作业范围
        changeCraneMoveRange(cwpCranes);
        LogPrinter.printCraneDividedInfo(cwpCranes);

        //计算当前时刻多少部桥机作业合适，决定是否减桥机、减几部桥机、从哪边减桥机
        autoDelCraneBeforeCurWork(dpResultLast, cwpCranes, cwpBays);
        cwpCranes = cwpData.getAllCranes();

        //判断桥机选择倍位时，是否经过驾驶台、烟囱等信息
        analyzeCraneIsThroughMachine(dpCraneSelectBays);

        //根据桥机是否经过烟囱、驾驶台等，计算每部桥机（在自己的作业范围内）选择每个倍位时的作业时间量
        changeWorkTimeByCraneIsThroughMachine(dpCraneSelectBays);

        //根据当前小倍位可作业的垫脚箱量与总垫脚箱量是否相同，计算每部桥机选择垫脚倍位时的作业时间量
        changeWorkTimeBySteppingCnt(dpCraneSelectBays);

        //根据重点倍位，计算每部桥机选择重点倍位时的作业时间量
        changeWorkTimeByKeyBay(dpCraneSelectBays);

        //根据分割倍位，计算每部桥机选择分割倍位时的作业时间量
        changeWorkTimeByDividedBay(dpCraneSelectBays);

        //根据桥机当前作业的倍位，重排CWP时尽量让桥机在原来的位置作业
        changeWorkTimeByCraneCurWorkVesselBay(dpCraneSelectBays);

        //根据上次DP选择的结果，增加该桥机选择这个倍位时的作业时间量
        changeWorkTimeByCraneLastSelectedBay(dpResultLast, dpCraneSelectBays);

        //根据桥机状态是否正在经过烟囱、驾驶台等，将桥机选择每个倍位时的作业时间量设置为0，即不作业状态???
        changeWorkTimeByCraneIsThroughMachineNow(cwpCranes, dpCraneSelectBays);

        //按贴现公式改变每部桥机选择每个倍位时的作业时间量
        LogPrinter.printChangeToDpInfo("之前", cwpCranes, cwpBays, dpCraneSelectBays);
        changeWorkTimeToDynamic(cwpCranes, dpCraneSelectBays);

        //根据桥机分块作业范围，计算每部桥机选择每个倍位时的作业时间量
        changeWorkTimeByCraneMoveRange(dpResultLast, dpCraneSelectBays);
//        if (!cwpData.getAutoDelCraneNow()) {
//            changeWorkTimeByCraneMoveRange(dpResultLast, dpCraneSelectBays);
//        }

        LogPrinter.printChangeToDpInfo("之后", cwpCranes, cwpBays, dpCraneSelectBays);

        //根据桥机作业范围内的作业量是否做完，设置桥机是否可以等待的状态
        changeCraneWorkDoneState(cwpCranes, cwpBays);

        DP dp = new DP();
        dpResult = dp.cwpKernel(cwpCranes, cwpBays, dpCraneSelectBays);

        //根据DP结果，分析DP少选择桥机的原因（1、是否发生少量垫脚箱避让 2、由于保持在上次选择的倍位作业导致DP在少数桥机选择的结果量更大
        // 3、是否放不下这么多桥机了，需要自动减桥机），是否进行再次DP
        dpResult = analyzeCurDpResult(dp, dpResult, dpResultLast, cwpCranes, cwpBays);

        //根据DP结果，计算每部桥机在所选倍位作业的最小时间(分割倍作业时间、桥机维修、加减桥机)，即找出启动倍
        long minWorkTime = obtainMinWorkTime(dpResult);

        //根据DP结果，以及桥机最小作业时间，对每部桥机的作业量进行编序
        realWork(dpResult, minWorkTime);

        search(depth + 1);

    }

    private void computeCurrentWorkTime(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        cwpData.setCurTotalWorkTime(0L);
        dpCraneSelectBays.clear();
        initDpCraneSelectBayWorkTime(cwpCranes, cwpBays);
        for (CWPBay cwpBay : cwpBays) {
            cwpBay.setDpCurrentTotalWorkTime(cwpVessel.getTotalWorkTime(cwpBay.getBayNo()));
            cwpData.addCurTotalWorkTime(cwpBay.getDpCurrentTotalWorkTime());
        }
        for (CWPCrane cwpCrane : cwpCranes) {
            for (CWPBay cwpBay : cwpBays) {
                DPPair dpPair = new DPPair<>(cwpCrane.getCraneNo(), cwpBay.getBayNo());
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                if (dpCraneSelectBay != null) {
                    dpCraneSelectBay.setDpDistance(Math.abs(cwpCrane.getDpCurrentWorkPosition() - cwpBay.getWorkPosition()));
                    if (!cwpCrane.isBreakdown() && !cwpCrane.isMaintainNow()) {
                        long workTime;
                        if (cwpData.getCurrentWorkTime() < cwpData.getStartWorkTime() + cwpConfiguration.getBreakDownCntTime()) {
                            if (isEffectBay(cwpBay)) {
                                workTime = 0L;
                            } else {
                                workTime = cwpVessel.getAvailableWorkTime(cwpBay.getBayNo(), cwpCrane);
                            }
                        } else {
                            workTime = cwpVessel.getAvailableWorkTime(cwpBay.getBayNo(), cwpCrane);
                        }
                        cwpBay.setDpAvailableWorkTime(workTime);
                        dpCraneSelectBay.setDpWorkTime(workTime);
                    }
                }
            }
        }
    }

    private boolean isDelOrAddCraneNow() {
        boolean isDelOrAddCraneNow = false;
        if (cwpData.isDelCraneNow() && !cwpData.isAddCraneNow()) {
            isDelOrAddCraneNow = true;
            delCraneFromCwpData();
            List<CWPCrane> cwpCranes = cwpData.getAllCranes();
            List<CWPBay> cwpBays = cwpData.getAllBays();
            clearData(cwpCranes, cwpBays);
            initDpCraneSelectBayWorkTime(cwpCranes, cwpBays);
            computeCurrentWorkTime(cwpCranes, cwpBays);
            divideCraneMoveRange(cwpCranes, cwpBays);
            cwpData.setDelCraneNow(false);
            cwpData.setDelCraneNum(null);
            cwpData.setDelCraneTime(null);
        } else if (!cwpData.isDelCraneNow() && cwpData.isAddCraneNow()) {
            isDelOrAddCraneNow = true;
            addCraneToCwpData();
            List<CWPCrane> cwpCranes = cwpData.getAllCranes();
            List<CWPBay> cwpBays = cwpData.getAllBays();
            clearData(cwpCranes, cwpBays);
            initDpCraneSelectBayWorkTime(cwpCranes, cwpBays);
            computeCurrentWorkTime(cwpCranes, cwpBays);
            divideCraneMoveRange(cwpCranes, cwpBays);
            cwpData.setAddCraneNow(false);
            cwpData.setAddCraneNum(null);
            cwpData.setAddCraneTime(null);
        } else if (cwpData.isDelCraneNow() && cwpData.isAddCraneNow()) {
            isDelOrAddCraneNow = true;
            delCraneFromCwpData();
            addCraneToCwpData();
            List<CWPCrane> cwpCranes = cwpData.getAllCranes();
            List<CWPBay> cwpBays = cwpData.getAllBays();
            clearData(cwpCranes, cwpBays);
            initDpCraneSelectBayWorkTime(cwpCranes, cwpBays);
            computeCurrentWorkTime(cwpCranes, cwpBays);
            divideCraneMoveRange(cwpCranes, cwpBays);
            cwpData.setDelCraneNow(false);
            cwpData.setDelCraneNum(null);
            cwpData.setDelCraneTime(null);
            cwpData.setAddCraneNow(false);
            cwpData.setAddCraneNum(null);
            cwpData.setAddCraneTime(null);
        }
        return isDelOrAddCraneNow;
    }

    private void delCraneFromCwpData() {
        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        if (cwpData.getVesselVisit().isDelCraneFromLeft()) {
            cwpData.removeCWPCrane(cwpCranes.get(0));
        }
        if (cwpData.getVesselVisit().isDelCraneFromRight()) {
            cwpData.removeCWPCrane(cwpCranes.get(cwpCranes.size() - 1));
        }
    }

    private void addCraneToCwpData() {
        List<CWPBay> cwpBays = cwpData.getAllBays();
        List<CWPCranePool> cwpCranePools = cwpData.getVesselVisit().getAllCWPCranePools();
        if (cwpData.getVesselVisit().isAddCraneFromLeft()) {
            CWPCrane cwpCraneAdd = cwpData.getVesselVisit().getCWPCraneByCraneNo(cwpCranePools.get(0).getCraneNo());
            CWPBay cwpBayFirst = cwpBays.get(0);
            cwpCraneAdd.setDpCurrentWorkPosition(cwpBayFirst.getWorkPosition() - 2 * cwpConfiguration.getCraneSafeSpan());
            cwpCraneAdd.setDpCurrentWorkTime(cwpData.getCurrentWorkTime());
            cwpData.addCWPCrane(cwpCraneAdd);
        }
        if (cwpData.getVesselVisit().isAddCraneFromRight()) {
            CWPCrane cwpCraneAdd = cwpData.getVesselVisit().getCWPCraneByCraneNo(cwpCranePools.get(cwpCranePools.size() - 1).getCraneNo());
            CWPBay cwpBayLast = cwpBays.get(cwpBays.size() - 1);
            cwpCraneAdd.setDpCurrentWorkPosition(cwpBayLast.getWorkPosition() + 2 * cwpConfiguration.getCraneSafeSpan());
            cwpCraneAdd.setDpCurrentWorkTime(cwpData.getCurrentWorkTime());
            cwpData.addCWPCrane(cwpCraneAdd);
        }
    }

    private boolean hasCraneWorkOrNotWorkNow() {
        boolean hasCraneWorkOrNotWorkNow = false;
        if (cwpData.isHasCraneCanWorkNow() && !cwpData.isHasCraneCanNotWorkNow()) {
            hasCraneWorkOrNotWorkNow = true;
            changeCraneWorkRangeByCraneCanWorkOrNotWorkNow();
            cwpData.setHasCraneCanWorkNow(false);
        } else if (cwpData.isHasCraneCanNotWorkNow() && !cwpData.isHasCraneCanWorkNow()) {
            hasCraneWorkOrNotWorkNow = true;
            changeCraneWorkRangeByCraneCanWorkOrNotWorkNow();
            cwpData.setHasCraneCanNotWorkNow(false);
        } else if (cwpData.isHasCraneCanWorkNow() && cwpData.isHasCraneCanNotWorkNow()) {
            hasCraneWorkOrNotWorkNow = true;
            changeCraneWorkRangeByCraneCanWorkOrNotWorkNow();
            cwpData.setHasCraneCanWorkNow(false);
            cwpData.setHasCraneCanNotWorkNow(false);
        }
        return hasCraneWorkOrNotWorkNow;
    }

    private void changeCraneWorkRangeByCraneCanWorkOrNotWorkNow() {
        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        List<CWPCrane> cwpCraneList = new ArrayList<>();
        for (CWPCrane cwpCrane : cwpCranes) {
            if (!cwpCrane.isMaintainNow() && !cwpCrane.isBreakdown()) {//桥机维修好了，那么状态一定是可作业的
                cwpCraneList.add(cwpCrane);
            }
        }
        List<CWPBay> cwpBays = cwpData.getAllBays();
        clearData(cwpCranes, cwpBays);
        initDpCraneSelectBayWorkTime(cwpCranes, cwpBays);
        computeCurrentWorkTime(cwpCraneList, cwpBays);
        divideCraneMoveRange(cwpCraneList, cwpBays);//有效桥机数
    }

    private void changeCraneMoveRange(List<CWPCrane> cwpCranes) {
        for (CWPCrane cwpCrane : cwpCranes) {
            Integer bayNoFrom = cwpCrane.getDpWorkBayNoFrom();
            CWPBay cwpBayFrom = cwpData.getCWPBayByBayNo(bayNoFrom);
            if (cwpBayFrom != null) {
                if (cwpBayFrom.isDividedBay()) {
                    if (cwpCrane.getDpWorkTimeFrom() < cwpConfiguration.getCraneMeanEfficiency()) {
                        //TODO:分割倍处理还需要优化
                        Integer nextBayNo = cwpData.getNextBayNo(bayNoFrom);
                        cwpCrane.setDpWorkBayNoFrom(nextBayNo);
                        cwpCrane.setDpWorkPositionFrom(cwpData.getCWPBayByBayNo(nextBayNo).getWorkPosition());
                    }
                }
            }
            Integer bayNoTo = cwpCrane.getDpWorkBayNoTo();
            CWPBay cwpBayTo = cwpData.getCWPBayByBayNo(bayNoTo);
            if (cwpBayTo != null) {
                if (cwpBayTo.isDividedBay()) {
                    if (cwpCrane.getDpWorkTimeTo() < cwpConfiguration.getCraneMeanEfficiency() - 30) {
                        Integer frontBayNo = cwpData.getFrontBayNo(bayNoTo);
                        cwpCrane.setDpWorkBayNoTo(frontBayNo);
                        cwpCrane.setDpWorkPositionTo(cwpData.getCWPBayByBayNo(frontBayNo).getWorkPosition());
                    }
                }
            }
        }
    }

    private void analyzeCraneIsThroughMachine(List<DPCraneSelectBay> dpCraneSelectBays) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            boolean isThroughMachine = false;
            for (CWPMachine cwpMachine : cwpData.getAllMachines()) {
                double machinePo = cwpMachine.getMachinePosition();
                if ((machinePo > cwpBay.getWorkPosition() && machinePo < cwpCrane.getDpCurrentWorkPosition())
                        || (machinePo > cwpCrane.getDpCurrentWorkPosition() && machinePo < cwpBay.getWorkPosition())) {
                    isThroughMachine = true;
                }
            }
            dpCraneSelectBay.setTroughMachine(isThroughMachine);
        }
    }

    private void changeWorkTimeByCraneIsThroughMachine(List<DPCraneSelectBay> dpCraneSelectBays) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            if (dpCraneSelectBay.getDpWorkTime() > 0) {
                if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1
                        && cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) < 1) {
                    if (dpCraneSelectBay.isTroughMachine()) {
                        if (!cwpData.isFirstRealWork()) {
                            if (dpCraneSelectBay.getDpWorkTime() <= cwpConfiguration.getCrossBarTime()) {
                                dpCraneSelectBay.setDpWorkTime(1L);
                            } else {
                                dpCraneSelectBay.setDpWorkTime(4L);
                            }
                        }
                    }
                }
            }
        }
    }

    private void changeWorkTimeBySteppingCnt(List<DPCraneSelectBay> dpCraneSelectBays) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            if (cwpBay.getDpAvailableWorkTime() > 0) {
                CWPHatch cwpHatch = cwpData.getCWPHatchByHatchId(cwpBay.getHatchId());
                List<Integer> bayNos = cwpHatch.getBayNos();
                if (cwpBay.getBayNo() % 2 == 1 && bayNos.size() == 3) {//奇数倍位号，且舱内有3个作业倍位，则判断为小倍位
                    if (cwpBay.getDpAvailableWorkTime().compareTo(cwpBay.getDpCurrentTotalWorkTime()) == 0) { //TODO: 只要小倍位可以作业，则优先于大倍位作业???
                        for (Integer bayNo : bayNos) {
                            if (bayNo % 2 == 0) { //将大倍位当前时刻总作业量加到小倍位箱量上
                                CWPBay cwpBayD = cwpData.getCWPBayByBayNo(bayNo);
                                dpCraneSelectBay.addDpWorkTime(cwpBayD.getDpCurrentTotalWorkTime());
                            }
                        }
                    }
                }
            }
        }
    }

    private void changeWorkTimeByKeyBay(List<DPCraneSelectBay> dpCraneSelectBays) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            if (cwpBay.isKeyBay()) {
                if (cwpBay.getDpAvailableWorkTime() > 0) {
                    dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeyBayWorkTime());
                }
            }
        }
    }

    private void changeWorkTimeByDividedBay(List<DPCraneSelectBay> dpCraneSelectBays) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            if (cwpBay.isDividedBay()) {
                if (cwpBay.getDpAvailableWorkTime() > 0) {
                    dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getDividedBayWorkTime());
                }
            }
        }
    }

    private void changeWorkTimeByCraneCurWorkVesselBay(List<DPCraneSelectBay> dpCraneSelectBays) {
        if (cwpData.isDoWorkCwp() && cwpData.getFirstDoWorkCwp()) {
            for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
                CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
                CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
                if (cwpBay.getDpAvailableWorkTime() > 0) {
                    if (cwpCrane.getWorkVesselBay() != null) {
                        if (cwpBay.getBayNo().equals(Integer.valueOf(cwpCrane.getWorkVesselBay()))) {
                            dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeyBayWorkTime());
                        }
                    }
                }
            }
            cwpData.setFirstDoWorkCwp(false);
        }
    }

    private void changeWorkTimeByCraneLastSelectedBay(DPResult dpResultLast, List<DPCraneSelectBay> dpCraneSelectBays) {
        for (DPPair dpPair : dpResultLast.getDpTraceBack()) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpPair.getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpPair.getSecond());
            if (cwpBay.getDpAvailableWorkTime() > 0) {
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                if (dpCraneSelectBay != null) {
                    //TODO:继续上次选择的倍位作业，具体应该加一个什么值
                    dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeepSelectedBayWorkTime());
                    //TODO:分割倍按分割量严格分割，桥机做完自己的量就离开???
//                    if (!cwpCrane.isMaintainNow()) {
//                        if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) < 0 ||
//                                cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) > 0) {
//                            if (cwpBay.isDividedBay()) {
//                                //即使是分割倍位，小于等于15关的分割倍，继续上次选择倍位的做下去
//                                if (cwpBay.getDpCurrentTotalWorkTime() > 15 * cwpConfiguration.getCraneMeanEfficiency()) {
//                                    dpCraneSelectBay.setDpWorkTime(0L);
//                                }
////                                dpCraneSelectBay.setDpWorkTime(0L);
//                            }
//                        }
//                    }
                }
            } else { //上次选择的倍位当前没有可作业量（一般是倍位量做完或者倍位量暂时没有暴露出来），尽量在这个舱内作业
                if (cwpCrane != null) {
                    CWPHatch cwpHatch = cwpData.getCWPHatchByHatchId(cwpBay.getHatchId());
                    List<Integer> bayNos = cwpHatch.getBayNos();
                    long maxDpWorkTime = getMaxDpWorkTimeInCraneMoveRange(cwpCrane, cwpBay);
                    for (Integer bayNo : bayNos) {
                        CWPBay cwpBay1 = cwpData.getCWPBayByBayNo(bayNo);
                        if (cwpBay1.getDpAvailableWorkTime() > 0) {
                            DPPair dpPair1 = new DPPair<>(cwpCrane.getCraneNo(), cwpBay1.getBayNo());
                            DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair1);
                            if (dpCraneSelectBay != null) {
                                long workTime = cwpBay.getDpTotalWorkTime();
//                                long workTime1 = cwpBay1.getDpCurrentTotalWorkTime();
//                                workTime = workTime > workTime1 ? workTime : workTime1;
                                if (maxDpWorkTime > workTime) {//其他倍位的最大作业量比上次选择倍位的总作业量还要大
                                    workTime = maxDpWorkTime;
                                }
                                dpCraneSelectBay.addDpWorkTime(workTime);
                            }
                        }
                    }
                }
            }
        }
    }

    private long getMaxDpWorkTimeInCraneMoveRange(CWPCrane cwpCrane, CWPBay cwpBayLast) {
        Long max = 0L;
        List<CWPBay> cwpBays = cwpData.getAllBays();
        for (CWPBay cwpBay : cwpBays) {
            if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1 &&
                    cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) < 1) {
                DPPair dpPair = new DPPair<>(cwpCrane.getCraneNo(), cwpBay.getBayNo());
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                if (dpCraneSelectBay != null) {
                    if (cwpBayLast != null) {
                        if (!cwpBay.getBayNo().equals(cwpBayLast.getBayNo())) {
                            max = Math.max(max, dpCraneSelectBay.getDpWorkTime());
                        }
                    } else {
                        max = Math.max(max, dpCraneSelectBay.getDpWorkTime());
                    }
                }
            }
        }
        return max;
    }

    private void changeWorkTimeByCraneIsThroughMachineNow(List<CWPCrane> cwpCranes, List<DPCraneSelectBay> dpCraneSelectBays) {
        for (CWPCrane cwpCrane : cwpCranes) {
            if (cwpCrane.isThroughMachineNow()) {
                List<DPCraneSelectBay> dpCraneSelectBayList = DPCraneSelectBay.getDpCraneSelectBaysByCrane(dpCraneSelectBays, cwpCrane.getCraneNo());
                for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBayList) {
                    dpCraneSelectBay.setDpWorkTime(0L);
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
                dpCraneSelectBay.setDpWorkTimeCopy(dpCraneSelectBay.getDpWorkTime());
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
                    dpCraneSelectBay.setDpWorkTimeCopyAfter(dynamicWorkTime);
                }
            }
//            for (int j = dpCraneSelectBayList.size() - 1; j >= 0; j--) {
//                DPCraneSelectBay dpCraneSelectBay = dpCraneSelectBayList.get(j);
//                if (dpCraneSelectBay.getDpWorkTime() > 0) {
//                    dpCraneSelectBay.setDpWorkTime(dpCraneSelectBay.getDpWorkTimeCopy());
//                    dpCraneSelectBay.setDpWorkTimeCopyAfter(dpCraneSelectBay.getDpWorkTimeCopy());
//                    break;
//                }
//            }
        }
    }

    private void changeWorkTimeByCraneMoveRange(DPResult dpResultLast, List<DPCraneSelectBay> dpCraneSelectBays) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            if (!cwpCrane.isBreakdown() && !cwpCrane.isMaintainNow() && !cwpCrane.isThroughMachineNow()) {
                //TODO:过驾驶台的桥机DP不考虑作业
                if (cwpBay.getDpAvailableWorkTime() > 0) {
                    if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1 &&
                            cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) < 1) {

                    } else {
                        //TODO:自己范围以外的量都设为1，是否合理？
                        //如果桥机移动范围界限正好在大倍位置上，则可以放宽范围
                        long workTime = dpCraneSelectBay.getDpWorkTime();
                        Integer bayNoFrom = cwpCrane.getDpWorkBayNoFrom();
                        Integer bayNoTo = cwpCrane.getDpWorkBayNoTo();
                        if (bayNoFrom % 2 == 0) {
                            Integer bayNoFromFront = cwpData.getFrontBayNo(bayNoFrom);
                            if (cwpBay.getBayNo().equals(bayNoFromFront)) {
                                dpCraneSelectBay.setDpWorkTime(workTime);
                            } else {
//                                dpCraneSelectBay.setDpWorkTime(1L);
                                setDpWorkTimeOutOfCraneMoveRange(cwpCrane, cwpBay, dpCraneSelectBay, dpCraneSelectBays);
                            }
                        } else if (bayNoTo % 2 == 0) {
                            Integer bayNoToNext = cwpData.getNextBayNo(bayNoTo);
                            if (cwpBay.getBayNo().equals(bayNoToNext)) {
                                dpCraneSelectBay.setDpWorkTime(workTime);
                            } else {
//                                dpCraneSelectBay.setDpWorkTime(1L);
                                setDpWorkTimeOutOfCraneMoveRange(cwpCrane, cwpBay, dpCraneSelectBay, dpCraneSelectBays);
                            }
                        } else {
//                            dpCraneSelectBay.setDpWorkTime(1L);
                            setDpWorkTimeOutOfCraneMoveRange(cwpCrane, cwpBay, dpCraneSelectBay, dpCraneSelectBays);
                        }

                        CWPCrane cwpCraneFront = cwpData.getFrontCWPCrane(cwpCrane.getCraneNo());
                        CWPCrane cwpCraneNext = cwpData.getNextCWPCrane(cwpCrane.getCraneNo());

                        //避免最后两边的桥机又回来作业自己分块的量、且这个倍位已经被其他桥机做了，则让旁边的桥机作业
                        if (craneInDpResult(cwpCrane, dpResultLast)) {
                            Integer lastSelectedBayNo = getCraneSelectBayInDpResult(cwpCrane.getCraneNo(), dpResultLast);
                            if (cwpBay.getBayNo().equals(lastSelectedBayNo)) {
                                if (cwpCraneFront != null) {
                                    long maxFrontCraneWorkTime = getMaxDpWorkTimeInCraneMoveRange(cwpCraneFront, cwpBay);
                                    //前一部桥机作业范围内倍位量，除了上次被旁边桥机选择的这个倍，其他倍位做完量为0、且是第一步桥机，这样第一部桥机就不会再回来作业了
                                    if (maxFrontCraneWorkTime == 0 && isFirstOrLastCrane(cwpCraneFront)) {
                                        dpCraneSelectBay.setDpWorkTime(workTime);
                                    }
                                }
                                if (cwpCraneNext != null) {
                                    long maxNextCraneWorkTime = getMaxDpWorkTimeInCraneMoveRange(cwpCraneNext, cwpBay);
                                    if (maxNextCraneWorkTime == 0 && isFirstOrLastCrane(cwpCraneNext)) {
                                        dpCraneSelectBay.setDpWorkTime(workTime);
                                    }
                                }
                                //如果上次选择的是分割倍，且量小于15关，则继续做下去，避免造成剩余很少的箱量的分割量
                                if (cwpBay.isDividedBay()) {
                                    dpCraneSelectBay.setDpWorkTime(workTime);
//                                    if (cwpBay.getDpCurrentTotalWorkTime() < 13 * cwpConfiguration.getCraneMeanEfficiency()) {
//                                        dpCraneSelectBay.setDpWorkTime(workTime);
//                                    }
                                }
                                //如果是左边第一部桥机，则继续做下去，条件有点大???
                                if (isFirstCrane(cwpCrane)) {
                                    if (cwpBay.getDpCurrentTotalWorkTime() < 13 * cwpConfiguration.getCraneMeanEfficiency()) {
                                        dpCraneSelectBay.setDpWorkTime(workTime);
                                    }
                                }
                                //如果是中间的桥机，旁边安全距离内的倍位不会被旁边的桥机选择（这个条件需要二次决策判断），则继续做下去

                            }
                        }

                        //避免最后两边的桥机又回来作业自己分块的量、且旁边的桥机离它近些，则让旁边的桥机作业

                        //避免桥机跨驾驶台去作业自己分块剩余的倍位量，而旁边的桥机自己分块的倍位量已经做完了，且旁边的桥机离它近些
                        long maxCraneWorkTime = getMaxDpWorkTimeInCraneMoveRange(cwpCrane, null);
                        if (cwpCraneFront != null) {
                            DPPair dpPairFront = new DPPair<>(cwpCraneFront.getCraneNo(), cwpBay.getBayNo());
                            DPCraneSelectBay dpCraneSelectBayFront = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPairFront);
                            if (dpCraneSelectBayFront != null) {
                                if (dpCraneSelectBayFront.isTroughMachine()) {
                                    if (maxCraneWorkTime == 0) {
                                        dpCraneSelectBay.setDpWorkTime(workTime);
                                    } else { //如果自己分块的作业量没做完，也是有可能继续作业下去的

                                    }
                                }
                            }
                        }
                        if (cwpCraneNext != null) {
                            DPPair dpPairNext = new DPPair<>(cwpCraneNext.getCraneNo(), cwpBay.getBayNo());
                            DPCraneSelectBay dpCraneSelectBayNext = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPairNext);
                            if (dpCraneSelectBayNext != null) {
                                if (dpCraneSelectBayNext.isTroughMachine()) {
                                    if (maxCraneWorkTime == 0) {
                                        dpCraneSelectBay.setDpWorkTime(workTime);
                                    }
                                }
                            }
                        }

                        //当桥机作业不属于自己分块的倍位，且自己分块的倍位作业量已经做完，且该倍位应该被作业的桥机在上次DP中没有被选中，则桥机继续做下去
                        if (maxCraneWorkTime == 0) {
                            if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) < 0) {
                                if (cwpCraneFront != null) {
                                    if (!craneInDpResult(cwpCraneFront, dpResultLast)) {
                                        if (cwpBay.getDpCurrentTotalWorkTime() < 13 * cwpConfiguration.getCraneMeanEfficiency()) {
                                            dpCraneSelectBay.setDpWorkTime(workTime);
                                        }
                                    } else { //如果上次DP选择了该桥机，除非该桥机只剩下该舱的量没有作业了

                                    }
                                }
                            }
                            if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) > 0) {
                                if (cwpCraneNext != null) {
                                    if (!craneInDpResult(cwpCraneNext, dpResultLast)) {
                                        if (cwpBay.getDpCurrentTotalWorkTime() < 13 * cwpConfiguration.getCraneMeanEfficiency()) {
                                            dpCraneSelectBay.setDpWorkTime(workTime);
                                        }
                                    }
                                }
                            }
                        }
//                dpCraneSelectBay.setDpWorkTime(1L);
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
            if (dpCraneSelectBay1 != null && dpCraneSelectBay1.getDpWorkTimeCopyAfter() > 0) {
                dpWTList.add(dpCraneSelectBay1.getDpWorkTimeCopyAfter());
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

    private DPResult analyzeCurDpResult(DP dp, DPResult dpResult, DPResult dpResultLast, List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        int curDpCraneNum = dpResult.getDpTraceBack().size();
        if (curDpCraneNum == 0) {
            return dpResult;
        }

//        for (DPPair dpPair : dpResult.getDpTraceBack()) {
//            String craneNo = (String) dpPair.getFirst();
//            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo(craneNo);
//            Integer curBayNo = (Integer) dpPair.getSecond();
//            CWPBay curCwpBay = cwpData.getCWPBayByBayNo(curBayNo);
//            Integer lastBayNo = getCraneSelectBayInDpResult(craneNo, dpResultLast);
//            //当前DP结果与上次DP结果不一样
//            if (!curBayNo.equals(lastBayNo)) { //有可能该桥机上次DP没有选择倍位作业
//                //判断该桥机是否发生由于作业范围限制而导致换倍作业
//
//                //判断该桥机是否满足垫脚箱作业的规则，且当前倍位在桥机作业范围之外
//                if (curCwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) < 0
//                        || curCwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) > 0) {
//                    CWPCrane cwpCraneFront = cwpData.getFrontCWPCrane(craneNo);
//                    CWPCrane cwpCraneNext = cwpData.getNextCWPCrane(craneNo);
//
//                }
//            }
//        }

        List<CWPCrane> availableCwpCraneList = AutoDelCrane.getAvailableCwpCraneList(cwpCranes);
        List<CWPCrane> reducedCraneList = getCurReducedCranesInLastDpResult(availableCwpCraneList, dpResult);
        int reducedCraneNum = reducedCraneList.size(); //排除了维修、故障、正在过驾驶台的桥机
        if (reducedCraneNum > 0) {
//            cwpData.setAutoDelCraneNow(true);
            cwpLogger.logInfo("The current DP reduced number of crane is " + reducedCraneNum);
        }
        return dpResult;
    }

    private List<CWPCrane> getCurReducedCranesInLastDpResult(List<CWPCrane> cwpCranes, DPResult dpResult) {
        List<CWPCrane> cwpCraneList = new ArrayList<>();
        for (CWPCrane cwpCrane : cwpCranes) {
            //排除维修、故障、正在过驾驶台的桥机
            if (!cwpCrane.isMaintainNow() && !cwpCrane.isBreakdown() && !cwpCrane.isThroughMachineNow()) {
                if (!craneInDpResult(cwpCrane, dpResult)) {
                    cwpCraneList.add(cwpCrane);
                }
            }
        }
        return cwpCraneList;
    }

    private boolean craneInDpResult(CWPCrane cwpCrane, DPResult dpResult) {
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            String craneNo = (String) dpPair.getFirst();
            if (craneNo.equals(cwpCrane.getCraneNo())) {
                return true;
            }
        }
        return false;
    }

    private Integer getCraneSelectBayInDpResult(String craneNo, DPResult dpResult) {
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            String craneNo1 = (String) dpPair.getFirst();
            if (craneNo1.equals(craneNo)) {
                return (Integer) dpPair.getSecond();
            }
        }
        return null;
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
        minWorkTime = analyzeCraneThroughMachine(minWorkTime);
        minWorkTime = analyzeCraneMaintainPlan(minWorkTime);
        minWorkTime = analyzeDelCrane(minWorkTime);
        minWorkTime = analyzeAddCrane(minWorkTime);
        return minWorkTime;
    }

    private Long analyzeCraneThroughMachine(Long minWorkTime) {
        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        for (CWPCrane cwpCrane : cwpCranes) {
            if (cwpCrane.isThroughMachineNow()) {
                long workTime = cwpData.getCurrentWorkTime() + minWorkTime;
                if (cwpCrane.getDpCurrentWorkTime() < workTime) {
                    minWorkTime = cwpCrane.getDpCurrentWorkTime() - cwpData.getCurrentWorkTime();
                }
            }
        }
        return minWorkTime;
    }

    private Long analyzeCraneMaintainPlan(Long minWorkTime) {
        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        for (CWPCrane cwpCrane : cwpCranes) {
            List<CWPCraneMaintainPlan> cwpCraneMaintainPlans = cwpCrane.getAllCWPCraneMaintainPlans();
            if (!cwpCraneMaintainPlans.isEmpty()) {
                long workTime = cwpData.getCurrentWorkTime() + minWorkTime;
                CWPCraneMaintainPlan cwpCraneMaintainPlan = cwpCraneMaintainPlans.get(0);
                long maintainStartTime = cwpCraneMaintainPlan.getMaintainStartTime().getTime() / 1000;
                long maintainEndTime = cwpCraneMaintainPlan.getMaintainEndTime().getTime() / 1000;
                if (!cwpCrane.isMaintainNow() && maintainStartTime <= workTime) {
                    cwpLogger.logInfo("Crane(No:" + cwpCrane.getCraneNo() + ") enters into the state of maintenance.");
                    minWorkTime = maintainStartTime - cwpData.getCurrentWorkTime();
                    cwpData.setHasCraneCanNotWorkNow(true);
                    cwpCrane.setMaintainNow(true);
                    cwpCrane.setBreakdown(true);
                } else if (cwpCrane.isMaintainNow() && maintainEndTime <= workTime) {
                    cwpLogger.logInfo("Crane(No:" + cwpCrane.getCraneNo() + ") completes maintenance.");
                    cwpData.setHasCraneCanWorkNow(true);
                    cwpCrane.setMaintainNow(false);
                    if (cwpCrane.isBreakdown()) {
                        cwpCrane.setBreakdown(false);
                    }
                    cwpCrane.removeCWPCraneMaintainPlan(cwpCraneMaintainPlan);//维修计划时间过了，则删除该维修计划
                    if (workTime - maintainEndTime > cwpConfiguration.getAddCraneTimeParam()) {
                        minWorkTime = maintainEndTime - cwpData.getCurrentWorkTime();
                    }
                }
            }
        }
        return minWorkTime;
    }

    private Long analyzeDelCrane(Long minWorkTime) {
        boolean isDelCraneNow = false;
        if (cwpData.getDelCraneNum() != null && cwpData.getDelCraneTime() != null) {
            long workTime = cwpData.getCurrentWorkTime() + minWorkTime;
            if (cwpData.getDelCraneTime() <= workTime) {
                cwpLogger.logInfo("It is time to delete one crane.");
                isDelCraneNow = true;
                if (workTime - cwpData.getDelCraneTime() > cwpConfiguration.getDelCraneTimeParam()) {
                    minWorkTime = cwpData.getDelCraneTime() - cwpData.getCurrentWorkTime();
                }
            }
        }
        cwpData.setDelCraneNow(isDelCraneNow);
        return minWorkTime;
    }

    private Long analyzeAddCrane(Long minWorkTime) {
        boolean isAddCraneNow = false;
        if (cwpData.getAddCraneNum() != null && cwpData.getAddCraneTime() != null) {
            long workTime = cwpData.getCurrentWorkTime() + minWorkTime;
            if (cwpData.getAddCraneTime() <= workTime) {
                cwpLogger.logInfo("It is time to add one crane.");
                isAddCraneNow = true;
                if (workTime - cwpData.getAddCraneTime() > cwpConfiguration.getAddCraneTimeParam()) {
                    minWorkTime = cwpData.getAddCraneTime() - cwpData.getCurrentWorkTime();
                }
            }
            if (cwpData.getAddCraneTime() - workTime <= cwpConfiguration.getAddCraneTimeParam()) {
                cwpLogger.logInfo("It is time to add one crane.");
                isAddCraneNow = true;
            }
        }
        cwpData.setAddCraneNow(isAddCraneNow);
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
                if (!cwpData.isFirstRealWork()) {
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
                    if (!cwpData.isFirstRealWork() && dpCraneSelectBay.isTroughMachine()) {//桥机置为正在移动状态
                        cwpCrane.setThroughMachineNow(true);
                    }
                    realMinWorkTime = 0L;
                }
                long realWorkTime = cwpVessel.doProcessOrder(cwpCrane, cwpBay, realMinWorkTime);
                if (minWorkTime > moveTime && !cwpData.isFirstRealWork() && dpCraneSelectBay.isTroughMachine()) {//桥机移过驾驶台后还可以继续作业
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
        boolean isFirstRealWork = !(maxRealWorkTime > 0) && cwpData.isFirstRealWork();
        cwpData.setFirstRealWork(isFirstRealWork);
        List<CWPCrane> cwpCranes = cwpData.getAllCranes();
        for (CWPCrane cwpCrane : cwpCranes) {
            if (!cwpCrane.isThroughMachineNow()) {//当前所有处于非移动状态的桥机加上相同的最大作业时间
                cwpCrane.addDpCurrentWorkTime(maxRealWorkTime);//使每部桥机在这次规划中作业相同的时间
            }
            if (cwpCrane.isThroughMachineNow()) {//看是否可以取消移动状态
                if (Math.abs(cwpData.getCurrentWorkTime() - cwpCrane.getDpCurrentWorkTime()) < cwpConfiguration.getCraneMeanEfficiency()) {
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
            cwpData.setCurrentWorkTime(minCurrentTime);
        } else {
            cwpData.setCurrentWorkTime(maxCurrentTime);
        }
    }

    private void autoDelCraneBeforeCurWork(DPResult dpResultLast, List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        List<CWPCrane> availableCwpCraneList = AutoDelCrane.getAvailableCwpCraneList(cwpCranes);
        //计算当前时刻，剩余作业量最大的是哪一条作业路
        List<CWPBay> maxCwpBayList = AutoDelCrane.getMaxWorkTimeCWPBayList(cwpConfiguration.getCraneSafeSpan(), cwpBays);
        if (maxCwpBayList.size() == 0) {
            return;
        }
        LogPrinter.printMaxCwpBay(maxCwpBayList);
        List<CWPBay> leftCwpBayList = AutoDelCrane.getLeftCwpBayList(cwpBays, maxCwpBayList);
        List<CWPBay> rightCwpBayList = AutoDelCrane.getRightCwpBayList(cwpBays, maxCwpBayList);
        long maxWorkTime = AutoDelCrane.getAllCurTotalWorkTime(maxCwpBayList);
        long leftAllWorkTime = AutoDelCrane.getAllCurTotalWorkTime(leftCwpBayList);
        long rightAllWorkTime = AutoDelCrane.getAllCurTotalWorkTime(rightCwpBayList);
        //计算上次DP选择哪部桥机作业剩余时间量最大的作业路
        String maxCwpCraneNo = AutoDelCrane.getMaxCwpCraneNoInMaxCwpBayList(dpResultLast, maxCwpBayList);
        if (maxCwpCraneNo != null) {
            cwpLogger.logInfo("The max road is selected by crane(No:" + maxCwpCraneNo + ") in last DP.");
            CWPCrane maxCwpCrane = cwpData.getCWPCraneByCraneNo(maxCwpCraneNo);
            List<CWPCrane> leftCwpCraneList = AutoDelCrane.getLeftCwpCraneList(availableCwpCraneList, maxCwpCrane);
            List<CWPCrane> rightCwpCraneList = AutoDelCrane.getRightCwpCraneList(availableCwpCraneList, maxCwpCrane);
            //根据公式计算左右两边是否减桥机、减几部桥机
            long leftExpectWorkTime = maxWorkTime * leftCwpCraneList.size();
            long rightExpectWorkTime = maxWorkTime * rightCwpCraneList.size();
            double leftResidue = (double) (leftExpectWorkTime - leftAllWorkTime) / (double) maxWorkTime;
            double rightResidue = (double) (rightExpectWorkTime - rightAllWorkTime) / (double) maxWorkTime;
            delCraneFromLeftAndRight("left", leftResidue, leftCwpCraneList, dpResultLast, cwpBays);
            delCraneFromLeftAndRight("right", rightResidue, rightCwpCraneList, dpResultLast, cwpBays);
        }
    }

    private void delCraneFromLeftAndRight(String side, double sideResidue, List<CWPCrane> sideCwpCraneList, DPResult dpResultLast, List<CWPBay> cwpBays) {
        cwpLogger.logInfo("The " + side + " reduced number of crane is: " + sideResidue);
        for (int i = 0; i < (int) sideResidue && i < sideCwpCraneList.size(); i++) {
            int k = side.equals("left") ? i : sideCwpCraneList.size() - i - 1;
            int c = side.equals("left") ? 1 : -1;
            CWPCrane reducedCwpCrane = sideCwpCraneList.get(k);
            CWPCrane sideCwpCrane = null;
            if (i + 1 < sideCwpCraneList.size()) {
                sideCwpCrane = sideCwpCraneList.get(k + c);
            }
            if (!delCraneProper(side, reducedCwpCrane, sideCwpCrane, dpResultLast, cwpBays)) {
                break;
            }
        }
    }

    private boolean delCraneProper(String side, CWPCrane reducedCwpCrane, CWPCrane sideCwpCrane, DPResult dpResultLast, List<CWPBay> cwpBays) {
        boolean proper = true;
        String reducedCraneNo = reducedCwpCrane.getCraneNo();
        Integer bayNo = getCraneSelectBayInDpResult(reducedCraneNo, dpResultLast);
        if (bayNo != null) { //需要减去的桥机不在上次DP结果中出现
            CWPBay cwpBay = cwpData.getCWPBayByBayNo(bayNo);
            CWPHatch cwpHatch = cwpData.getCWPHatchByHatchId(cwpBay.getHatchId());
            List<Integer> bayNos = cwpHatch.getBayNos();
            //上次DP选择的舱是否还有剩余作业量
            boolean proper1 = true;
            for (Integer bayNo1 : bayNos) {
                CWPBay cwpBay1 = cwpData.getCWPBayByBayNo(bayNo1);
                if (cwpBay1.getDpCurrentTotalWorkTime() > 0) {
                    proper = false;
                    proper1 = false;
                }
            }
            //上次DP选择的舱靠近最左/右的倍位是否还有剩余作业量
            int c = side.equals("left") ? -1 : 1;
            for (CWPBay cwpBay1 : cwpBays) {
                if (cwpBay1.getWorkPosition().compareTo(cwpBay.getWorkPosition()) == c) {
                    if (cwpBay1.getDpCurrentTotalWorkTime() > 0) {
                        proper = false;
                    }
                }
            }
            if (proper1) {
                //分析下一部将要被减去的桥机，剩余多少作业量
                if (sideCwpCrane != null) {
                    long maxCurTotalWorkTime = getMaxDpCurTotalWorkTimeInCraneMoveRange(sideCwpCrane, null, cwpBays);
                    long autoDelCraneWT = cwpConfiguration.getAutoDelCraneAmount() * cwpConfiguration.getCraneMeanEfficiency();
                    if (maxCurTotalWorkTime <= autoDelCraneWT) {
                        proper = true;
                    }
                }
            }
        }
        if (proper) {
            cwpLogger.logInfo("The crane(No:" + reducedCraneNo + ") is deleted properly.");
            cwpData.removeCWPCrane(reducedCwpCrane);
//            cwpData.setAutoDelCraneNow(true);
            List<CWPCrane> curCwpCranes = cwpData.getAllCranes();
            computeCurrentWorkTime(curCwpCranes, cwpBays);
        }
        return proper;
    }

    private long getMaxDpCurTotalWorkTimeInCraneMoveRange(CWPCrane cwpCrane, CWPBay cwpBayLast, List<CWPBay> cwpBays) {
        Long max = 0L;
        for (CWPBay cwpBay : cwpBays) {
            if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1 &&
                    cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) < 1) {
                if (cwpBayLast != null) {
                    if (!cwpBay.getBayNo().equals(cwpBayLast.getBayNo())) {
                        max = Math.max(max, cwpBay.getDpCurrentTotalWorkTime());
                    }
                } else {
                    max = Math.max(max, cwpBay.getDpCurrentTotalWorkTime());
                }
            }
        }
        return max;
    }

    private void changeCraneWorkDoneState(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        for (CWPCrane cwpCrane : cwpCranes) {
            long maxDpCurTotalWT = getMaxDpCurTotalWorkTimeInCraneMoveRange(cwpCrane, null, cwpBays);
            if (maxDpCurTotalWT == 0) {
                cwpCrane.setWorkDone(true);
            }
        }
    }

    private CWPCrane whoIsTheMoreProperDeletedCrane(DPResult dpResultLast, List<CWPCrane> cwpCranes, List<CWPBay> cwpBays) {
        CWPCrane cwpCraneLeft = cwpCranes.get(0);
        CWPCrane cwpCraneRight = cwpCranes.get(cwpCranes.size() - 1);
        long leftCraneWorkTime = getAllWorkTimeInCraneMoveRange(dpResultLast, cwpCraneLeft, cwpBays);
        long rightCraneWorkTime = getAllWorkTimeInCraneMoveRange(dpResultLast, cwpCraneRight, cwpBays);
        Integer bayNoLeft = getCraneSelectBayInDpResult(cwpCraneLeft.getCraneNo(), dpResultLast);
        Integer bayNoRight = getCraneSelectBayInDpResult(cwpCraneRight.getCraneNo(), dpResultLast);
        long leftMinusRight = leftCraneWorkTime - rightCraneWorkTime;
        if (bayNoLeft == null && bayNoRight != null) {
            leftMinusRight = -1;
        } else if (bayNoLeft != null && bayNoRight == null) {
            leftMinusRight = 1;
        }
        if (leftMinusRight < 0) {
            if ((leftCraneWorkTime > 5 * cwpConfiguration.getCraneMeanEfficiency())
                    || leftCraneWorkTime == 0
                    || bayNoLeft == null) {
                return cwpCraneLeft;
            }
            return null;
        } else {
            if (rightCraneWorkTime > 5 * cwpConfiguration.getCraneMeanEfficiency()
                    || rightCraneWorkTime == 0
                    || bayNoRight == null) {
                return cwpCraneRight;
            }
            return null;
        }
    }

    private long getAllWorkTimeInCraneMoveRange(DPResult dpResultLast, CWPCrane cwpCrane, List<CWPBay> cwpBays) {
        long allWorkTime = 0;
        //先判断是否中间桥机做完了，然后减桥机后被迫两边的桥机移动的数目，往移动桥机数目少的地方减

        //桥机作业范围内，当前还剩有多少总量没有做
        for (CWPBay cwpBay : cwpBays) {
            if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1 &&
                    cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) < 1) {
                if (!cwpBay.isDividedBay()) {
                    allWorkTime += cwpBay.getDpCurrentTotalWorkTime();
                } else {//分割倍位、且是自己在做，则该倍位量加上???
                    Integer bayNo = getCraneSelectBayInDpResult(cwpCrane.getCraneNo(), dpResultLast);
                    if (cwpBay.getBayNo().equals(bayNo)) {
                        allWorkTime += cwpBay.getDpCurrentTotalWorkTime();
                    }
                }
            }
        }
        //判断过驾驶台
        String craneNo = cwpCrane.getCraneNo();
        if (cwpData.getFrontCWPCrane(craneNo) == null) {
            CWPCrane cwpCraneNext = cwpData.getNextCWPCrane(craneNo);
            if (cwpCraneNext != null) {
                for (CWPBay cwpBay : cwpBays) {
                    if (cwpBay.getWorkPosition().compareTo(cwpCraneNext.getDpCurrentWorkPosition()) < 0) {
                        DPPair dpPair = new DPPair<>(cwpCraneNext.getCraneNo(), cwpBay.getBayNo());
                        DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                        if (dpCraneSelectBay.getDpWorkTime() > 0 && dpCraneSelectBay.isTroughMachine()) {
                            allWorkTime += 3 * cwpConfiguration.getCrossBarTime();
                        }
                    }
                }
            }
        } else if (cwpData.getNextCWPCrane(craneNo) == null) {
            CWPCrane cwpCraneFront = cwpData.getFrontCWPCrane(craneNo);
            if (cwpCraneFront != null) {
                for (CWPBay cwpBay : cwpBays) {
                    if (cwpBay.getWorkPosition().compareTo(cwpCraneFront.getDpCurrentWorkPosition()) > 0) {
                        DPPair dpPair = new DPPair<>(cwpCraneFront.getCraneNo(), cwpBay.getBayNo());
                        DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                        if (dpCraneSelectBay.getDpWorkTime() > 0 && dpCraneSelectBay.isTroughMachine()) {
                            allWorkTime += 3 * cwpConfiguration.getCrossBarTime();
                        }
                    }
                }
            }
        }
        return allWorkTime;
    }

    private boolean isFinishWorkCrane(CWPCrane reducedCrane, List<CWPBay> cwpBays) {
        boolean isFinishWork = true;
        for (CWPBay cwpBay : cwpBays) {
            if (cwpBay.getWorkPosition().compareTo(reducedCrane.getDpWorkPositionFrom()) > -1 &&
                    cwpBay.getWorkPosition().compareTo(reducedCrane.getDpWorkPositionTo()) < 1) {
                if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                    isFinishWork = false;
                }
            }
        }
        return isFinishWork;
    }

    private boolean isFirstOrLastCrane(CWPCrane cwpCraneDiff) {
        return cwpData.getNextCWPCrane(cwpCraneDiff.getCraneNo()) == null || cwpData.getFrontCWPCrane(cwpCraneDiff.getCraneNo()) == null;
    }

    private boolean isFirstCrane(CWPCrane cwpCrane) {
        return cwpData.getFrontCWPCrane(cwpCrane.getCraneNo()) == null;
    }

}
