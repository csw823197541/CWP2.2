package com.cwp.single.cwp.process;

import com.cwp.entity.CWPBay;
import com.cwp.entity.CWPConfiguration;
import com.cwp.entity.CWPCrane;
import com.cwp.log.CWPLogger;
import com.cwp.log.CWPLoggerFactory;
import com.cwp.single.cwp.cwpvessel.CWPData;
import com.cwp.single.cwp.dp.DPCraneSelectBay;
import com.cwp.single.cwp.dp.DPPair;
import com.cwp.single.cwp.dp.DPResult;
import com.cwp.single.cwp.processorder.CWPHatch;

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

    static void changeDpWTByCraneThroughMachine(CWPData cwpData, List<DPCraneSelectBay> dpCraneSelectBays) {
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

    static void changeDpWTByParameters(MethodParameter methodParameter, CWPData cwpData, List<DPCraneSelectBay> dpCraneSelectBays) {
        CWPConfiguration cwpConfiguration = cwpData.getVesselVisit().getCwpConfiguration();
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            CWPCrane cwpCrane = cwpData.getCWPCraneByCraneNo((String) dpCraneSelectBay.getDpPair().getFirst());
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpCraneSelectBay.getDpPair().getSecond());
            CWPHatch cwpHatch = cwpData.getCWPHatchByHatchId(cwpBay.getHatchId());
            List<Integer> bayNos = cwpHatch.getBayNos();
            if (cwpBay.getDpAvailableWorkTime() > 0) {
                if (methodParameter.getSteppingCnt()) {
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

    static void changeDpWTByParameters(DPResult dpResultLast, MethodParameter methodParameter, CWPData cwpData, List<DPCraneSelectBay> dpCraneSelectBays) {
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
                                    double distance1 = cwpCrane.getDpWorkPositionFrom() - cwpBay.getWorkPosition();
                                    double distance2 = cwpBay.getWorkPosition() - cwpCrane.getDpWorkPositionTo();
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

    static boolean changeDpWTByDpAgain(DPResult dpResult, DPResult dpResultLast, List<DPCraneSelectBay> dpCraneSelectBays, MethodParameter methodParameter, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getVesselVisit().getCwpConfiguration();
        boolean dpAgain = false;
        Long minWorkTime = Long.MAX_VALUE;
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            CWPBay cwpBay = cwpData.getCWPBayByBayNo((Integer) dpPair.getSecond());
            long craneMinWorkTime = cwpBay.getDpAvailableWorkTime();
            minWorkTime = Math.min(minWorkTime, craneMinWorkTime);
        }
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            String craneNo = (String) dpPair.getFirst();
            CWPCrane cwpCraneCur = cwpData.getCWPCraneByCraneNo(craneNo);
            Integer bayNoCur = (Integer) dpPair.getSecond();
            CWPBay cwpBayCur = cwpData.getCWPBayByBayNo(bayNoCur);
            Integer bayNoLast = PublicMethod.getSelectBayNoInDpResult(craneNo, dpResultLast);
            boolean changeToLastSelectBay = false;
            boolean changeSideCraneSelectBay = false;
            if (methodParameter.getChangeToLastSelectBay()) {
                if (bayNoLast != null && !bayNoLast.equals(bayNoCur)) { //桥机当前选择与上次不同倍位
                    CWPBay cwpBayLast = cwpData.getCWPBayByBayNo(bayNoLast);
                    if (cwpBayLast.getDpAvailableWorkTime() > 0 && !cwpBayLast.isDividedBay()) { //上次选择倍位有量，且不是分割倍
                        Integer bayNoFront = PublicMethod.getFrontOrNextBayNoInDpResult("front", craneNo, dpResult);
                        Integer bayNoNext = PublicMethod.getFrontOrNextBayNoInDpResult("next", craneNo, dpResult);
                        if (bayNoFront != null && bayNoNext == null) {
                            CWPBay cwpBayFront = cwpData.getCWPBayByBayNo(bayNoFront);
                            if (cwpBayLast.getWorkPosition() - cwpBayFront.getWorkPosition() >= 2 * cwpConfiguration.getCraneSafeSpan()) {
                                changeToLastSelectBay = true;
                            }
                        } else if (bayNoFront != null && bayNoNext != null) {
                            CWPBay cwpBayFront = cwpData.getCWPBayByBayNo(bayNoFront);
                            CWPBay cwpBayNext = cwpData.getCWPBayByBayNo(bayNoNext);
                            if (cwpBayLast.getWorkPosition() - cwpBayFront.getWorkPosition() >= 2 * cwpConfiguration.getCraneSafeSpan()
                                    && cwpBayNext.getWorkPosition() - cwpBayLast.getWorkPosition() >= 2 * cwpConfiguration.getCraneSafeSpan()) {
                                changeToLastSelectBay = true;
                            }
                        } else if (bayNoFront == null && bayNoNext != null) {
                            CWPBay cwpBayNext = cwpData.getCWPBayByBayNo(bayNoNext);
                            if (cwpBayNext.getWorkPosition() - cwpBayLast.getWorkPosition() >= 2 * cwpConfiguration.getCraneSafeSpan()) {
                                changeToLastSelectBay = true;
                            }
                        } else if (bayNoFront == null && bayNoNext == null) {
                            changeToLastSelectBay = true;
                        }
                    }
                    if (cwpBayCur.isKeyBay() && !cwpBayLast.isKeyBay()) { //如果是重点倍的话，还是可以回来作业的
                        changeToLastSelectBay = !changeToLastSelectBay && changeToLastSelectBay;
                    }
                }
            }
            if (changeToLastSelectBay) {
                cwpLogger.logInfo("Change dpWorkTime to lastSelectBay working(" + craneNo + ":" + bayNoLast + ").");
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, new DPPair<>(craneNo, bayNoLast));
                if (dpCraneSelectBay != null) {
                    dpCraneSelectBay.addDpWorkTime(cwpConfiguration.getKeepSelectedBayWorkTime());
                }
            }
            if (methodParameter.getChangeSideCraneWork()) {
                if (!bayNoCur.equals(bayNoLast)) { //桥机当前选择与上次不同倍位
                    long maxWorkTime = PublicMethod.getMaxDpCurTotalWorkTimeInCraneMoveRange(cwpCraneCur, cwpBayCur, cwpData.getAllBays());
                    if (maxWorkTime == 0) { //除了这个倍位，没有其他作业量了
                        if (bayNoLast != null) { //该桥机上次有选择倍位作业
                            CWPBay cwpBayLast = cwpData.getCWPBayByBayNo(bayNoLast);
                            double distance = cwpBayCur.getWorkPosition() - cwpBayLast.getWorkPosition();
                            if ((distance > cwpConfiguration.getCraneSafeSpan() && cwpData.getFrontCWPCrane(craneNo) == null)
                                    || (distance < -cwpConfiguration.getCraneSafeSpan() && cwpData.getNextCWPCrane(craneNo) == null)) { //判断是否为第一部或者最后一部桥机，同时有没有往中间行过来作业
                                if (minWorkTime < 20 * cwpConfiguration.getCraneMeanEfficiency() && dpResult.getDpTraceBack().size() > 1) { //并且是作业很少的时间量，那么就判断该桥机不要作业这个倍位了
                                    changeSideCraneSelectBay = true;
                                }
                                //如果该桥机当前选择的倍位，在桥机安全距离内，不包括自身，没有其他倍位作业量了，说明不会受其他桥机影响，还是有可能可以继续作业下去的
                                boolean noneWork = true;
                                for (CWPBay cwpBay : cwpData.getAllBays()) {
                                    double distance1 = Math.abs(cwpBay.getWorkPosition() - cwpBayCur.getWorkPosition());
                                    if (distance1 < 2 * cwpConfiguration.getCraneSafeSpan() && distance1 > cwpConfiguration.getCraneSafeSpan() / 2) {
                                        if (cwpBay.getDpCurrentTotalWorkTime() > 0) {
                                            noneWork = false;
                                        }
                                    }
                                }
                                changeSideCraneSelectBay = !(changeSideCraneSelectBay && noneWork) && changeSideCraneSelectBay;
                            }
                        }
                        if (bayNoLast == null && !cwpData.getFirstDoCwp()) { //该桥机上次没有选择倍位作业(非第一次DP)，并且是第一部或者最后一部桥机，那么就判断该桥机不要作业这个倍位了
                            if (minWorkTime < 20 * cwpConfiguration.getCraneMeanEfficiency() && PublicMethod.isFirstOrLastCrane(craneNo, cwpData)) {
                                changeSideCraneSelectBay = true;
                            }
                            String craneNoLast = PublicMethod.getSelectCraneNoInDpResult(bayNoCur, dpResultLast); //当前倍位上次DP已经有桥机在作业了
                            if (minWorkTime >= 20 * cwpConfiguration.getCraneMeanEfficiency() && craneNoLast != null && PublicMethod.isFirstOrLastCrane(craneNo, cwpData)) {
                                changeSideCraneSelectBay = true;
                            }
                        }
                    }
                }
            }
            if (changeSideCraneSelectBay) {
                cwpLogger.logInfo("Change dpWorkTime to 0 to control the side crane(" + craneNo + ":" + bayNoCur + ").");
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                if (dpCraneSelectBay != null) {
                    dpCraneSelectBay.setDpWorkTime(0L);
                }
            }
            dpAgain = dpAgain || changeToLastSelectBay || changeSideCraneSelectBay;
        }
        return dpAgain;
    }
}
