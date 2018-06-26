package com.cwp.single.cwp.process;

import com.cwp.config.CWPDefaultValue;
import com.cwp.config.CWPDomain;
import com.cwp.entity.*;
import com.cwp.log.CWPLogger;
import com.cwp.log.CWPLoggerFactory;
import com.cwp.single.cwp.cwpvessel.CWPData;
import com.cwp.single.cwp.dp.DPCraneSelectBay;
import com.cwp.single.cwp.dp.DPPair;
import com.cwp.single.cwp.dp.DPResult;
import com.cwp.single.cwp.processorder.CWPHatch;
import com.cwp.utils.CalculateUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by csw on 2017/8/9.
 * Description:
 */
public class ChangeDpWTMethod {

    private static CWPLogger cwpLogger = CWPLoggerFactory.getCWPLogger();

    static void changeDpWTByCranePhysicRange(List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        if (cwpData.getVesselVisit().getCWPCraneMoveRangeList().size() == 1) {
            for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
                String craneNo = (String) dpCraneSelectBay.getDpPair().getFirst();
                Integer bayNo = (Integer) dpCraneSelectBay.getDpPair().getSecond();
                CWPCraneMoveRange cwpCraneMoveRange = cwpData.getVesselVisit().getCWPCraneMoveRangeByCraneNo(craneNo);
                if (cwpCraneMoveRange != null) {
                    Integer bayNoSt = Integer.valueOf(cwpCraneMoveRange.getStartBayNo());
                    Integer bayNoEd = Integer.valueOf(cwpCraneMoveRange.getEndBayNo());
                    if (bayNoSt.compareTo(bayNoEd) < 0) {
                        if (bayNo.compareTo(bayNoSt) < 0 || bayNo.compareTo(bayNoEd) > 0) {
                            dpCraneSelectBay.setDpWorkTime(0L);
                        }
                    } else if (bayNoSt.compareTo(bayNoEd) > 0) {
                        if (bayNo.compareTo(bayNoSt) > 0 || bayNo.compareTo(bayNoEd) < 0) {
                            dpCraneSelectBay.setDpWorkTime(0L);
                        }
                    }
                }
            }
        }
    }

    static void changeDpWTByMachineBothSideNumber(List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getVesselVisit().getCwpConfiguration();
        List<CWPMachine> cwpMachineList = cwpData.getAllMachines();
        for (CWPMachine cwpMachine : cwpMachineList) {
            int leftNum = 0, rightNum = 0;
            long leftWT = Long.MAX_VALUE, rightWT = Long.MAX_VALUE;
            List<DPCraneSelectBay> dpCraneSelectBayListL = new ArrayList<>();
            List<DPCraneSelectBay> dpCraneSelectBayListR = new ArrayList<>();
            for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
                CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
                CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
                if (dpCraneSelectBay.getDpWorkTime() > 0) {
                    if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1
                            && cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) < 1) {
                        if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                            if (cwpBay.getWorkPosition().compareTo(cwpMachine.getMachinePosition()) < 0) {
                                leftNum++;
                                leftWT = Math.min(leftWT, cwpBay.getDpCurrentTotalWorkTime());
                                dpCraneSelectBayListL.add(dpCraneSelectBay);
                            }
                            if (cwpBay.getWorkPosition().compareTo(cwpMachine.getMachinePosition()) > 0) {
                                rightNum++;
                                rightWT = Math.min(rightWT, cwpBay.getDpCurrentTotalWorkTime());
                                dpCraneSelectBayListR.add(dpCraneSelectBay);
                            }
                        }
                    }
                }
            }
            if (leftNum > 0 && rightNum > 0) {
                if (leftNum > rightNum) {
                    for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBayListL) {
                        if (dpCraneSelectBay.getDpWorkTime() >= rightWT) {
                            dpCraneSelectBay.setDpWorkTime(rightWT - cwpConfiguration.getCraneMeanEfficiency());
                        }
                    }
                } else {
                    for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBayListR) {
                        if (dpCraneSelectBay.getDpWorkTime() >= leftWT) {
                            dpCraneSelectBay.setDpWorkTime(leftWT - cwpConfiguration.getCraneMeanEfficiency());
                        }
                    }
                }
            }
        }
    }

    static void changeDpWTByCraneThroughMachine(List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getVesselVisit().getCwpConfiguration();
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            if (dpCraneSelectBay.getDpWorkTime() > 0) {
                if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) > -1
                        && cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) < 1) {
                    if (dpCraneSelectBay.isTroughMachine()) {
                        if (!cwpData.getFirstDoCwp()) {
                            if (dpCraneSelectBay.getDpWorkTime() <= cwpConfiguration.getCrossBarTime()) {
                                dpCraneSelectBay.setDpWorkTime(3L);
                            } else {
                                dpCraneSelectBay.setDpWorkTime(4L);
                            }
                        }
                    }
                }
            }
        }
    }

    static void changeDpWTByParameters(MethodParameter methodParameter, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getVesselVisit().getCwpConfiguration();
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            CWPHatch cwpHatch = cwpData.getCWPHatchByHatchId(cwpBay.getHatchId());
            List<Integer> bayNos = cwpHatch.getBayNos();
            if (cwpBay.getDpAvailableWorkTime() > 0) {
                if (methodParameter.getSteppingCnt()) {
                    if (cwpBay.getBayNo() % 2 == 1 && bayNos.size() == 3) { //奇数倍位号，且舱内有3个作业倍位，则判断为小倍位
                        if (cwpBay.getDpAvailableWorkTime().compareTo(cwpBay.getDpCurrentTotalWorkTime()) == 0) {
                            //TODO: 只要小倍位可以作业，则优先于大倍位作业???
                            for (Integer bayNo : bayNos) {
                                if (bayNo % 2 == 0) { //将大倍位当前时刻总作业量加到小倍位箱量上
                                    CWPBay cwpBayD = cwpData.getCWPBayByBayNo(bayNo);
                                    dpCraneSelectBay.addDpWorkTime(cwpBayD.getDpCurrentTotalWorkTime());
                                    dpCraneSelectBay.addDpWorkTime(CWPDefaultValue.keyBayWorkTime);
//                                    if (cwpBayD.getDpCurrentTotalWorkTime().compareTo(cwpBayD.getDpAvailableWorkTime()) > 0) {
//                                        //大倍位不能一次性做完，则切换到小倍位做装船
//                                        dpCraneSelectBay.addDpWorkTime(CWPDefaultValue.keyBayWorkTime);
//                                    }
                                }
                            }
                        }
                    }
                }
                if (methodParameter.getKeyBay()) {
                    if (cwpBay.isKeyBay()) {
                        dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeyBayWorkTime());
                    }
                }
                if (methodParameter.getDividedBay()) {
                    if (cwpBay.isDividedBay()) {
                        dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getDividedBayWorkTime());
                    }
                }
                if (methodParameter.getCurWorkVesselBay()) {
                    if (cwpData.getDoWorkCwp() && cwpData.getFirstDoCwp()) {
                        if (cwpCrane.getWorkVesselBay() != null) {
                            if (cwpBay.getBayNo().equals(Integer.valueOf(cwpCrane.getWorkVesselBay()))) {
                                dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeyBayWorkTime());
                            }
                        }
                    }
                }
            }
        }
        if (methodParameter.getCurWorkVesselBay() && cwpData.getDoWorkCwp()) {
            cwpData.setFirstDoCwp(false);
        }
    }

    static void changeDpWTByParameters(DPResult dpResultLast, MethodParameter methodParameter, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getVesselVisit().getCwpConfiguration();
        for (DPPair dpPair : dpResultLast.getDpTraceBack()) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpPair.getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpPair.getSecond());
            DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
            if (cwpCrane != null) { //有可能上次DP选择的桥机当前时刻已被减去
                if (cwpBay.getDpAvailableWorkTime() > 0) {
                    if (dpCraneSelectBay != null) {
                        if (methodParameter.getKeepLastSelectBay()) {
                            //TODO:继续上次选择的倍位作业，具体应该加一个什么值
                            long maxDpCurTotalWorkTime = PublicMethod.getMaxDpCurTotalWorkTimeInCraneMoveRange(cwpCrane, null, cwpData.getAllBays());
//                            long maxDpCurTotalWorkTime = PublicMethod.getMaxDpWorkTimeInCraneMoveRange(cwpCrane, cwpBay, cwpData.getAllBays(), dpCraneSelectBays);
                            if (maxDpCurTotalWorkTime > 0) {
                                dpCraneSelectBay.addDpWorkTime(maxDpCurTotalWorkTime);
                            }
//                            dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeyBayWorkTime());
                        }
                        if (methodParameter.getStrictDividedBay()) {
                            //TODO:分割倍按分割量严格分割，桥机做完自己的量就离开???
                            if (cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionFrom()) < 0
                                    || cwpBay.getWorkPosition().compareTo(cwpCrane.getDpWorkPositionTo()) > 0) {
                                if (cwpBay.isDividedBay()) { //必须是该桥机做的分割倍位，才严格分割
//                                    double distance1 = cwpCrane.getDpWorkPositionFrom() - cwpBay.getWorkPosition();
//                                    double distance2 = cwpBay.getWorkPosition() - cwpCrane.getDpWorkPositionTo();
                                    double distance1 = CalculateUtil.sub(cwpCrane.getDpWorkPositionFrom(), cwpBay.getWorkPosition());
                                    double distance2 = CalculateUtil.sub(cwpBay.getWorkPosition(), cwpCrane.getDpWorkPositionTo());
                                    double distance = Math.max(distance1, distance2);
                                    if (distance < cwpConfiguration.getCraneSafeSpan() / 2) {
                                        //即使是分割倍位，小于等于15关的分割倍，继续上次选择倍位的做下去
//                                        if (cwpBay.getDpCurrentTotalWorkTime() > 15 * cwpConfiguration.getCraneMeanEfficiency()) {
//                                            dpCraneSelectBay.setDpWorkTime(3L);
//                                        }
                                        dpCraneSelectBay.setDpWorkTime(3L); //????
                                    }
                                }
                            }
                        }
                    }
                }
                if (cwpBay.getDpAvailableWorkTime() == 0) { //上次选择的倍位当前没有可作业量（一般是倍位量做完或者倍位量暂时没有暴露出来），尽量在这个舱内作业
                    CWPHatch cwpHatch = cwpData.getCWPHatchByHatchId(cwpBay.getHatchId());
                    List<Integer> bayNos = cwpHatch.getBayNos();
                    if (methodParameter.getKeepOneHatchWork()) {
                        long maxDpWorkTime = PublicMethod.getMaxDpWorkTimeInCraneMoveRange(cwpCrane, cwpBay, cwpData.getAllBays(), dpCraneSelectBays);
                        for (Integer bayNo : bayNos) {
                            CWPBay cwpBay1 = cwpData.getCWPBayByBayNo(bayNo);
                            if (cwpBay1.getDpAvailableWorkTime() > 0) {
                                DPPair dpPair1 = new DPPair<>(cwpCrane.getCraneNo(), cwpBay1.getBayNo());
                                DPCraneSelectBay dpCraneSelectBay1 = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair1);
                                if (dpCraneSelectBay1 != null) {
                                    long workTime = cwpBay.getDpTotalWorkTime();
                                    if (maxDpWorkTime > workTime) {//其他倍位的最大作业量比上次选择倍位的总作业量还要大???
                                        workTime = maxDpWorkTime;
                                    }
                                    dpCraneSelectBay1.addDpWorkTime(workTime);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    static void changeDpWTByCraneThroughMachineNow(List<CWPCrane> cwpCranes, List<DPCraneSelectBay> dpCraneSelectBays) {
        for (CWPCrane cwpCrane : cwpCranes) {
            if (cwpCrane.isThroughMachineNow()) {
                List<DPCraneSelectBay> dpCraneSelectBayList = DPCraneSelectBay.getDpCraneSelectBaysByCrane(dpCraneSelectBays, cwpCrane.getCraneNo());
                for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBayList) {
                    dpCraneSelectBay.setDpWorkTime(0L);
                }
            }
        }
    }

    static void setDpWorkTimeOutOfCraneMoveRange(CWPCrane cwpCrane, CWPBay cwpBay, DPCraneSelectBay dpCraneSelectBay, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
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

    static boolean changeToDpAgainByLastSelectBay(DPResult dpResult, DPResult dpResultLast, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getVesselVisit().getCwpConfiguration();
        MethodParameter methodParameter = cwpData.getMethodParameter();
        boolean dpAgain = false;
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            String craneNo = (String) dpPair.getFirst();
            Integer bayNoCur = (Integer) dpPair.getSecond();
            CWPBay cwpBayCur = cwpData.getCWPBayByBayNo(bayNoCur);
            Integer bayNoLast = PublicMethod.getSelectBayNoInDpResult(craneNo, dpResultLast);
            //--------------在不受两边桥机影响的情况下，让桥机保持在上一个倍位继续作业-------------
            boolean changeToLastSelectBay = false;
            if (methodParameter.getChangeToLastSelectBay()) {
                if (bayNoLast != null && !bayNoLast.equals(bayNoCur)) { //桥机当前选择与上次不同倍位
                    CWPBay cwpBayLast = cwpData.getCWPBayByBayNo(bayNoLast);
                    boolean littleBay = bayNoCur % 2 == 1 && cwpBayCur.getDpAvailableWorkTime().equals(cwpBayCur.getDpCurrentTotalWorkTime()) && cwpBayCur.getHatchId().equals(cwpBayLast.getHatchId());
                    if (cwpBayLast.getDpAvailableWorkTime() > 0 && !cwpBayLast.isDividedBay() && !littleBay) { //上次选择倍位有量，且不是分割倍
                        Integer bayNoFront = PublicMethod.getLeftOrRightBayNoInDpResult(CWPDomain.LEFT, craneNo, dpResult);
                        Integer bayNoNext = PublicMethod.getLeftOrRightBayNoInDpResult(CWPDomain.RIGHT, craneNo, dpResult);
                        if (bayNoFront != null && bayNoNext == null) {
                            CWPBay cwpBayFront = cwpData.getCWPBayByBayNo(bayNoFront);
                            double distance = CalculateUtil.sub(cwpBayLast.getWorkPosition(), cwpBayFront.getWorkPosition());
                            if (distance >= 2 * cwpConfiguration.getCraneSafeSpan()) {
                                changeToLastSelectBay = true;
                            }
                        } else if (bayNoFront != null) {
                            CWPBay cwpBayFront = cwpData.getCWPBayByBayNo(bayNoFront);
                            CWPBay cwpBayNext = cwpData.getCWPBayByBayNo(bayNoNext);
                            double distance1 = CalculateUtil.sub(cwpBayLast.getWorkPosition(), cwpBayFront.getWorkPosition());
                            double distance2 = CalculateUtil.sub(cwpBayNext.getWorkPosition(), cwpBayLast.getWorkPosition());
                            if (distance1 >= 2 * cwpConfiguration.getCraneSafeSpan() && distance2 >= 2 * cwpConfiguration.getCraneSafeSpan()) {
                                changeToLastSelectBay = true;
                            }
                        } else if (bayNoNext != null) {
                            CWPBay cwpBayNext = cwpData.getCWPBayByBayNo(bayNoNext);
                            double distance = CalculateUtil.sub(cwpBayNext.getWorkPosition(), cwpBayLast.getWorkPosition());
                            if (distance >= 2 * cwpConfiguration.getCraneSafeSpan()) {
                                changeToLastSelectBay = true;
                            }
                        } else {
                            changeToLastSelectBay = true;
                        }
                    }
                    if (dpResult.getDpTraceBack().size() != 1) { //如果不是最后只剩下一部桥机
                        if (cwpBayCur.isKeyBay() && !cwpBayLast.isKeyBay()) { //如果当前选择是重点倍的话，还是可以放弃上次选择倍位的
                            changeToLastSelectBay = false;
                        }
                    }
                }
            }
            if (changeToLastSelectBay) {
                cwpLogger.logDebug("桥机放弃当前选择(" + craneNo + ":" + bayNoCur + ")，继续选择上次作业的倍位作业(" + craneNo + ":" + bayNoLast + ").");
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, new DPPair<>(craneNo, bayNoLast));
                DPCraneSelectBay dpCraneSelectBayCur = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, new DPPair<>(craneNo, bayNoCur));
                if (dpCraneSelectBay != null) {
                    dpCraneSelectBay.addDpWorkTime(CWPDefaultValue.keepSelectedBayWorkTime);
                    if (dpCraneSelectBayCur != null) {
                        if (dpCraneSelectBayCur.getDpWorkTime().compareTo(dpCraneSelectBay.getDpWorkTime()) > 0) {
                            dpCraneSelectBay.addDpWorkTime(dpCraneSelectBayCur.getDpWorkTime());
                        }
                    }
                }
            }
            dpAgain = dpAgain || changeToLastSelectBay;
        }
        return dpAgain;
    }

    static boolean changeToDpAgainBySteppingCnt(DPResult dpResult, DPResult dpResultLast, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        MethodParameter methodParameter = cwpData.getMethodParameter();
        boolean dpAgain = false;
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            String craneNo = (String) dpPair.getFirst();
            CWPCrane cwpCraneCur = cwpData.getCWPCraneByCraneNo(craneNo);
            Integer bayNoCur = (Integer) dpPair.getSecond();
            CWPBay cwpBayCur = cwpData.getCWPBayByBayNo(bayNoCur);
            //-------------根据当前DP的选择，有桥机作业垫脚箱时，让旁边的桥等待，不要被迫去选择其他倍位作业，导致桥机均分量发生改变、桥机移动次数多等情况--------------
            boolean changeDpBySteppingCnt = false;
            if (methodParameter.getChangeDpBySteppingCnt()) {
                List<Integer> bayNos = cwpData.getCWPHatchByHatchId(cwpBayCur.getHatchId()).getBayNos();
                if (cwpBayCur.getBayNo() % 2 == 1 && bayNos.size() == 3) { //奇数倍位号，且舱内有3个作业倍位，则判断为小倍位
                    if (cwpBayCur.getDpAvailableWorkTime() < CWPDefaultValue.steppingCntWaitTime && cwpBayCur.getDpAvailableWorkTime() > 0) {
                        Integer bayNoLast = PublicMethod.getSelectBayNoInDpResult(craneNo, dpResultLast);
                        if (bayNoLast != null) { //该桥机上次选择的倍位不能为空
                            CWPBay cwpBayLast = cwpData.getCWPBayByBayNo(bayNoLast);
                            if (cwpBayCur.getHatchId().equals(cwpBayLast.getHatchId())) { //该桥机同舱作业垫脚
                                changeDpBySteppingCnt = analyzeWhichCraneWait(cwpCraneCur, cwpBayCur, cwpBayLast, dpResultLast, dpResult, cwpData);
                            }
                        }
                    }
                }
            }
            dpAgain = dpAgain || changeDpBySteppingCnt;
        }
        return dpAgain;
    }

    private static boolean analyzeWhichCraneWait(CWPCrane cwpCraneCur, CWPBay cwpBayCur, CWPBay cwpBayLast, DPResult dpResultLast, DPResult dpResult, CWPData cwpData) {
        boolean changeDpBySteppingCnt = false;
        String side = cwpBayCur.getWorkPosition().compareTo(cwpBayLast.getWorkPosition()) > 0 ? CWPDomain.RIGHT : CWPDomain.LEFT;
        Integer bayNoSide = PublicMethod.getLeftOrRightBayNoInDpResult(side, cwpCraneCur.getCraneNo(), dpResultLast);
        if (bayNoSide != null) {
            CWPBay cwpBaySide = cwpData.getCWPBayByBayNo(bayNoSide);
            CWPCrane cwpCraneSide = cwpData.getCWPCraneByCraneNo(PublicMethod.getSelectCraneNoInDpResult(bayNoSide, dpResultLast));
            if (cwpCraneSide != null) { //有可能上次选择作业旁边倍位的桥机已经下路了
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(cwpData.getDpCraneSelectBays(), new DPPair<>(cwpCraneSide.getCraneNo(), bayNoSide));
                if (dpCraneSelectBay != null && dpCraneSelectBay.getDpWorkTime() > 4L) { //说明旁边桥机上次选择作业的倍位是它作业范围内的倍位，后面应该继续作业下去
                    double distance = Math.abs(CalculateUtil.sub(cwpBaySide.getWorkPosition(), cwpBayCur.getWorkPosition()));
                    if (distance < 2 * cwpData.getCwpConfiguration().getCraneSafeSpan()) { //说明旁边桥机是因为安全距离不够被逼走的
                        Integer bayNo = PublicMethod.getSelectBayNoInDpResult(cwpCraneSide.getCraneNo(), dpResult);
                        if (bayNo != null) {
                            CWPBay cwpBay = cwpData.getCWPBayByBayNo(bayNo); //旁边桥机当前选择的倍位
                            if (cwpBayCur.getDpAvailableWorkTime().compareTo(cwpBay.getDpAvailableWorkTime()) < 0) { //当前桥机作业垫脚的时间比旁边桥机当前选择倍位作业的量少，那旁边的桥机还是等待吧
                                //旁边桥机放弃当前选择，桥机不作业，置为等待状态
                                cwpLogger.logDebug("发现桥机一定要作业小倍位(No:" + cwpCraneCur.getCraneNo() + ":" + cwpBayCur.getBayNo() + ")的垫脚" + cwpBayCur.getDpAvailableWorkTime() / cwpData.getCwpConfiguration().getCraneMeanEfficiency() + "关，让旁边的桥机(" + cwpCraneSide.getCraneNo() + ")等待.");
                                setCraneWaitState(cwpCraneSide, bayNo, cwpData);
                                cwpCraneSide.setWorkDone(true);
                                changeDpBySteppingCnt = true;
                            }
                        }
                    }
                }
            }
        }
        return changeDpBySteppingCnt;
    }

    static boolean changeToDpAgainByLoadSteppingCnt(DPResult dpResult, DPResult dpResultLast, List<DPCraneSelectBay> dpCraneSelectBays, CWPData cwpData) {
        MethodParameter methodParameter = cwpData.getMethodParameter();
        boolean dpAgain = false;
        for (DPPair dpPair : dpResultLast.getDpTraceBack()) {
            String craneNo = (String) dpPair.getFirst();
            CWPCrane cwpCraneLast = cwpData.getCWPCraneByCraneNo(craneNo);
            Integer bayNoLast = (Integer) dpPair.getSecond();
            CWPBay cwpBayLast = cwpData.getCWPBayByBayNo(bayNoLast);
            //-------------根据上次与当前DP的选择，发现本来这次选择应该作业垫脚，但是旁边桥机是重点路、或是需要连续避让几部桥机，
            // 导致该桥机没有作业垫脚，选择了其它舱作业、或者是旁边的桥机没有选择倍位作业而等待，并且这种情况会影响重点路的按时完成--------------
            boolean changeByLoadSteppingCnt = false;
            if (methodParameter.getChangeDpByLoadSteppingCnt()) {
                if (bayNoLast % 2 == 0) { //上次选择大倍位置作业
                    if (cwpBayLast.getDpAvailableWorkTime() == 0 && cwpBayLast.getDpCurrentTotalWorkTime() > 0) { //大倍位有总量，但是可作业量为0
                        Integer bayNoCur = PublicMethod.getSelectBayNoInDpResult(craneNo, dpResult);
                        if (bayNoCur != null) {
                            CWPBay cwpBayCur = cwpData.getCWPBayByBayNo(bayNoCur);
                            if (!cwpBayCur.getHatchId().equals(cwpBayLast.getHatchId())) { //当前桥机选择换舱作业，这个条件很重要
                                CWPBay cwpBayL = getLittleCwpBay(cwpBayLast, cwpData);
                                if (cwpBayL != null) {
                                    //小倍位不作业，大倍位的量留到最后会影响重点路按时完成，这个条件很重要
                                    changeByLoadSteppingCnt = analyzeWhichCraneWaitOrSelectSteppingBay(cwpCraneLast, cwpBayCur, cwpBayL, cwpBayLast, dpResult, cwpData);
                                }
                            }
                        } else {
                            if (PublicMethod.isFirstOrLastCrane(craneNo, cwpData)) { //一定是旁边的桥机？？？
                                CWPBay cwpBayL = getLittleCwpBay(cwpBayLast, cwpData);
                                if (cwpBayL != null) {
                                    //小倍位不作业，大倍位的量留到最后会影响重点路按时完成，这个条件很重要
                                    changeByLoadSteppingCnt = analyzeWhichCraneWaitOrSelectSteppingBay(cwpCraneLast, cwpBayL, cwpBayLast, dpResult, cwpData);
                                }
                            }
                        }
                    }
                }
                if (bayNoLast % 2 == 1) { //TODO:上次选择小倍位置作业
                    if (cwpBayLast.getDpAvailableWorkTime() > 0) {
                        List<CWPBay> maxCwpBayList = PublicMethod.getMaxDpCurTotalWorkTimeCWPBayList(cwpData.getAllBays(), cwpData);
                        long maxWT = PublicMethod.getCurTotalWorkTime(maxCwpBayList);
                        CWPBay cwpBayD = PublicMethod.getBigCWPBay(cwpBayLast, cwpData);
                        if (cwpBayD != null) {
                            List<CWPBay> sideCwpBayList = PublicMethod.getSideCwpBayListInSafeSpan(cwpBayD, cwpBayLast, cwpData);
                            long sideWT = PublicMethod.getCurTotalWorkTime(sideCwpBayList);
                            long bayDWT = cwpBayD.getDpCurrentTotalWorkTime();
                            if (sideWT + bayDWT > maxWT) {
                                cwpLogger.logDebug("小倍位(No: " + bayNoLast + ")的垫脚(" + cwpBayLast.getDpAvailableWorkTime() / cwpData.getCwpConfiguration().getCraneMeanEfficiency() + "关箱量)影响到大倍位的作业.");
                                Integer bayNoCur = PublicMethod.getSelectBayNoInDpResult(craneNo, dpResult);
                                if (bayNoCur != null) {
                                    CWPBay cwpBayCur = cwpData.getCWPBayByBayNo(bayNoCur);
                                    setCraneSelectSteppingBay(cwpCraneLast, cwpBayLast, cwpData); //该桥机继续选择小倍位作业下去
                                    long availableT = cwpBayCur.getDpAvailableWorkTime();
                                    long waitTime = CWPDefaultValue.waitMove * cwpData.getCwpConfiguration().getCraneMeanEfficiency();
                                    if (availableT > 0 && availableT == cwpBayCur.getDpCurrentTotalWorkTime() && availableT <= waitTime) {
                                        String side = cwpBayCur.getWorkPosition().compareTo(cwpBayLast.getWorkPosition()) > 0 ? CWPDomain.LEFT : CWPDomain.RIGHT;
                                        CWPCrane cwpCraneSide = PublicMethod.getSideCWPCrane(side, craneNo, cwpData);
                                        if (cwpCraneSide != null) {
                                            Integer bayNoSelect = PublicMethod.getSelectBayNoInDpResult(cwpCraneSide.getCraneNo(), dpResult);
                                            if (bayNoSelect == null) {
                                                PublicMethod.setCraneSelectNoneBay(cwpCraneSide, dpCraneSelectBays);
                                                cwpCraneSide.setWorkDone(true);
                                            } else {
                                                setCraneWaitState(cwpCraneSide, bayNoSelect, cwpData);
                                                cwpCraneSide.setWorkDone(true);
                                            }
                                        }
                                    }
                                    changeByLoadSteppingCnt = true;
                                } else {
                                    if (PublicMethod.isFirstOrLastCrane(craneNo, cwpData)) { //一定是旁边的桥机？？？
                                        changeByLoadSteppingCnt = analyzeWhichCraneWaitOrSelectSteppingBay(cwpCraneLast, cwpBayLast, cwpBayD, dpResult, cwpData);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            dpAgain = dpAgain || changeByLoadSteppingCnt;
        }
        return dpAgain;
    }

    private static CWPBay getLittleCwpBay(CWPBay cwpBayLast, CWPData cwpData) {
        List<Integer> bayNos = cwpData.getCWPHatchByHatchId(cwpBayLast.getHatchId()).getBayNos();
        CWPBay cwpBayL = null;
        for (Integer bayNo : bayNos) {
            if (!bayNo.equals(cwpBayLast.getBayNo()) && bayNo % 2 == 1) { //小倍位
                CWPBay cwpBay = cwpData.getCWPBayByBayNo(bayNo);
                long availableT = cwpBay.getDpAvailableWorkTime();
                long waitTime = CWPDefaultValue.waitMove * cwpData.getCwpConfiguration().getCraneMeanEfficiency();
                if (availableT > 0 && availableT == cwpBay.getDpCurrentTotalWorkTime() && availableT <= waitTime) {
                    List<CWPBay> maxCwpBayList = PublicMethod.getMaxDpCurTotalWorkTimeCWPBayList(cwpData.getAllBays(), cwpData);
                    long maxWT = PublicMethod.getCurTotalWorkTime(maxCwpBayList);
                    List<CWPBay> sideCwpBayList = PublicMethod.getSideCwpBayListInSafeSpan(cwpBayLast, cwpBay, cwpData);
                    long sideWT = PublicMethod.getCurTotalWorkTime(sideCwpBayList);
                    long bayLastWT = cwpBayLast.getDpCurrentTotalWorkTime();
                    if (sideWT + bayLastWT > maxWT) {
                        cwpBayL = cwpBay;
                        cwpLogger.logDebug("小倍位(No: " + bayNo + ")的垫脚(" + availableT / cwpData.getCwpConfiguration().getCraneMeanEfficiency() + "关箱量)影响到大倍位的作业.");
                    }
                }
            }
        }
        return cwpBayL;
    }

    private static boolean analyzeWhichCraneWaitOrSelectSteppingBay(CWPCrane cwpCraneLast, CWPBay cwpBayL, CWPBay cwpBayLast, DPResult dpResult, CWPData cwpData) {
        cwpLogger.logDebug("发现桥机(未选择倍位，是两边桥机)一定要作业小倍位(No:" + cwpCraneLast.getCraneNo() + ":" + cwpBayL.getBayNo() + ")的垫脚" + cwpBayL.getDpAvailableWorkTime() / cwpData.getCwpConfiguration().getCraneMeanEfficiency() + "关.");
        boolean changeByLoadSteppingCnt = false;
        String side = cwpBayL.getWorkPosition().compareTo(cwpBayLast.getWorkPosition()) < 0 ? CWPDomain.LEFT : CWPDomain.RIGHT;
        CWPCrane cwpCraneSide = PublicMethod.getSideCWPCrane(side, cwpCraneLast.getCraneNo(), cwpData);
        if (cwpCraneSide != null) {
            Integer bayNoSide = PublicMethod.getSelectBayNoInDpResult(cwpCraneSide.getCraneNo(), dpResult);
            CWPBay cwpBayInSafeSpan = PublicMethod.getSideCwpBayInSafeSpan(side, cwpBayL, cwpData);
            int moveCraneNum = getSideCraneMoveNum(1, side, cwpCraneSide.getCraneNo(), cwpBayInSafeSpan, dpResult, cwpData);
            if (moveCraneNum == 1) {
                //旁边桥机等待，自己继续作业垫脚
                setCraneWaitState(cwpCraneSide, bayNoSide, cwpData);
                cwpCraneSide.setWorkDone(true);
                changeByLoadSteppingCnt = true;
            } else if (moveCraneNum > 1) {
                //由于自己是两边桥机，自己等待，让旁边桥机行过来作业垫脚
                setCraneWaitState(cwpCraneLast, null, cwpData);
                cwpCraneLast.setWorkDone(true);
                setCraneSelectSteppingBay(cwpCraneSide, cwpBayL, cwpData);
                changeByLoadSteppingCnt = true;
            }
        }
        return changeByLoadSteppingCnt;
    }

    private static boolean analyzeWhichCraneWaitOrSelectSteppingBay(CWPCrane cwpCraneLast, CWPBay cwpBayCur, CWPBay cwpBayL, CWPBay cwpBayLast, DPResult dpResult, CWPData cwpData) {
        cwpLogger.logDebug("发现桥机(已选择其它倍位(No:" + cwpCraneLast.getCraneNo() + ":" + cwpBayCur.getBayNo() + "))一定要作业小倍位(No:" + cwpCraneLast.getCraneNo() + ":" + cwpBayL.getBayNo() + ")的垫脚" + cwpBayL.getDpAvailableWorkTime() / cwpData.getCwpConfiguration().getCraneMeanEfficiency() + "关.");
        boolean changeByLoadSteppingCnt = false;
        String side = cwpBayL.getWorkPosition().compareTo(cwpBayLast.getWorkPosition()) < 0 ? CWPDomain.LEFT : CWPDomain.RIGHT;
        CWPCrane cwpCraneSide = PublicMethod.getSideCWPCrane(side, cwpCraneLast.getCraneNo(), cwpData);
        if (cwpCraneSide != null) {
            //自己作业垫脚，需要旁边移动几部桥机
            Integer bayNoSide = PublicMethod.getSelectBayNoInDpResult(cwpCraneSide.getCraneNo(), dpResult);
            CWPBay cwpBayInSafeSpan = PublicMethod.getSideCwpBayInSafeSpan(side, cwpBayL, cwpData);
            int moveCraneNum = getSideCraneMoveNum(1, side, cwpCraneSide.getCraneNo(), cwpBayInSafeSpan, dpResult, cwpData);
            //让旁边的桥机来作业垫脚，需要移动几部桥机
            String side1 = side.equals(CWPDomain.LEFT) ? CWPDomain.RIGHT : CWPDomain.LEFT;
            int moveCraneNum1 = getSideCraneMoveNum(0, side1, cwpCraneSide.getCraneNo(), cwpBayL, dpResult, cwpData);
            if (moveCraneNum <= moveCraneNum1 && moveCraneNum == 1) {
                //旁边桥机等待，自己继续作业垫脚
                setCraneWaitState(cwpCraneSide, bayNoSide, cwpData);
                cwpCraneSide.setWorkDone(true);
                changeByLoadSteppingCnt = true;
            } else if (moveCraneNum1 <= 1) {
                //自己等待，让旁边桥机行过来作业垫脚
                setCraneWaitState(cwpCraneLast, cwpBayCur.getBayNo(), cwpData);
                cwpCraneLast.setWorkDone(true);
                setCraneSelectSteppingBay(cwpCraneSide, cwpBayL, cwpData);
                changeByLoadSteppingCnt = true;
            }
        }
        return changeByLoadSteppingCnt;
    }

    private static int getSideCraneMoveNum(int num, String side, String craneNo, CWPBay cwpBay, DPResult dpResult, CWPData cwpData) {
        Integer bayNoLeftOrRight = PublicMethod.getLeftOrRightBayNoInDpResult(side, craneNo, dpResult);
        if (bayNoLeftOrRight != null) {
            CWPBay cwpBayLeftOrRight = cwpData.getCWPBayByBayNo(bayNoLeftOrRight);
            double distance = Math.abs(CalculateUtil.sub(cwpBay.getWorkPosition(), cwpBayLeftOrRight.getWorkPosition()));
            if (distance < 2 * cwpData.getCwpConfiguration().getCraneSafeSpan()) {
                num = num + 1;
                String craneNoLeft = PublicMethod.getSelectCraneNoInDpResult(bayNoLeftOrRight, dpResult);
                CWPBay cwpBayInSafeSpan = PublicMethod.getSideCwpBayInSafeSpan(side, cwpBay, cwpData);
                getSideCraneMoveNum(num, side, craneNoLeft, cwpBayInSafeSpan, dpResult, cwpData);
            } else {
                return num;
            }
        }
        return num;
    }

    private static void setCraneWaitState(CWPCrane cwpCrane, Integer bayNo, CWPData cwpData) {
        cwpLogger.logDebug("桥机放弃当前的选择(No:" + cwpCrane.getCraneNo() + ": " + bayNo + ")，不作业任何倍位，置为等待状态.");
        List<DPCraneSelectBay> dpCraneSelectBayList = DPCraneSelectBay.getDpCraneSelectBaysByCrane(cwpData.getDpCraneSelectBays(), cwpCrane.getCraneNo());
        for (DPCraneSelectBay dpCraneSelectBay1 : dpCraneSelectBayList) {
            dpCraneSelectBay1.setDpWorkTime(0L);
        }
    }

    private static void setCraneSelectSteppingBay(CWPCrane cwpCrane, CWPBay cwpBay, CWPData cwpData) {
        cwpLogger.logDebug("桥机选择作业垫脚箱(No:" + cwpCrane.getCraneNo() + ": " + cwpBay.getBayNo() + ").");
        DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(cwpData.getDpCraneSelectBays(), new DPPair<>(cwpCrane.getCraneNo(), cwpBay.getBayNo()));
        if (dpCraneSelectBay != null) {
            dpCraneSelectBay.addDpWorkTime(10 * CWPDefaultValue.keepSelectedBayWorkTime);
        }
    }

}
