package com.cwp.single.cwp.process;

import com.cwp.config.CWPDomain;
import com.cwp.entity.CWPBay;
import com.cwp.entity.CWPConfiguration;
import com.cwp.entity.CWPCrane;
import com.cwp.log.CWPLogger;
import com.cwp.log.CWPLoggerFactory;
import com.cwp.single.cwp.cwpvessel.CWPData;
import com.cwp.single.cwp.dp.DPCraneSelectBay;
import com.cwp.single.cwp.dp.DPPair;
import com.cwp.single.cwp.dp.DPResult;
import com.cwp.utils.CalculateUtil;
import com.cwp.utils.LogPrinter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by csw on 2017/7/20.
 * Description:
 */
class AutoDelCraneMethod {

    private static CWPLogger cwpLogger = CWPLoggerFactory.getCWPLogger();

    private static void sortCwpBayByWorkPosition(List<CWPBay> cwpBayList) {
        Collections.sort(cwpBayList, new Comparator<CWPBay>() {
            @Override
            public int compare(CWPBay o1, CWPBay o2) {
                return o1.getWorkPosition().compareTo(o2.getWorkPosition());
            }
        });
    }

    static List<CWPBay> getMaxWorkTimeCWPBayList(double craneSafeSpan, List<CWPBay> cwpBays) {
        long maxWorkTime = Long.MIN_VALUE;
        List<CWPBay> maxCwpBayList = new ArrayList<>();
        for (int j = 0; j < cwpBays.size(); j++) {
            CWPBay cwpBayJ = cwpBays.get(j);
            if (cwpBayJ.getDpCurrentTotalWorkTime() > 0) {
                int k = j;
                Long tempWorkTime = 0L;
                List<CWPBay> tempCwpBayList = new ArrayList<>();
                for (; k < cwpBays.size(); k++) {
                    CWPBay cwpBayK = cwpBays.get(k);
                    double distance = CalculateUtil.sub(cwpBayK.getWorkPosition(), cwpBayJ.getWorkPosition());
                    if (distance < 2 * craneSafeSpan) {
                        if (cwpBayK.getDpCurrentTotalWorkTime() > 0 || (cwpBayK.getDpCurrentTotalWorkTime() == 0 && cwpBayK.isKeyBay())) { //???
                            tempWorkTime += cwpBayK.getDpCurrentTotalWorkTime();
                            tempCwpBayList.add(cwpBayK);
                        }
                    } else {
                        if (tempWorkTime > maxWorkTime) {
                            maxWorkTime = tempWorkTime;
                            maxCwpBayList.clear();
                            maxCwpBayList.addAll(tempCwpBayList);
                        }
                        break;
                    }
                }
            }
        }
        List<CWPBay> cwpBayList = new ArrayList<>();
        for (CWPBay cwpBay : maxCwpBayList) {
            if (cwpBay.isKeyBay()) { //量最大的路是重点路
                cwpBayList.add(cwpBay);
            }
        }
        return cwpBayList;
    }

    static List<CWPBay> getSideCwpBayList(String side, List<CWPBay> cwpBays, List<CWPBay> cwpBayList) {
        List<CWPBay> sideCwpBayList = new ArrayList<>();
        if (cwpBayList.isEmpty() || cwpBays.isEmpty()) {
            return sideCwpBayList;
        }
        sortCwpBayByWorkPosition(cwpBayList);
        for (CWPBay cwpBay : cwpBays) {
            if (side.equals(CWPDomain.LEFT)) {
                if (cwpBay.getWorkPosition().compareTo(cwpBayList.get(0).getWorkPosition()) < 0) {
                    sideCwpBayList.add(cwpBay);
                }
            }
            if (side.equals(CWPDomain.RIGHT)) {
                if (cwpBay.getWorkPosition().compareTo(cwpBayList.get(cwpBayList.size() - 1).getWorkPosition()) > 0) {
                    sideCwpBayList.add(cwpBay);
                }
            }
        }
        return sideCwpBayList;
    }

    static String getMaxCwpCraneNoInMaxCwpBayList(DPResult dpResult, List<CWPBay> maxCwpBayList) {
        List<String> craneNoList = new ArrayList<>();
        for (DPPair dpPair : dpResult.getDpTraceBack()) {
            String craneNo = (String) dpPair.getFirst();
            Integer bayNo = (Integer) dpPair.getSecond();
            for (CWPBay cwpBay : maxCwpBayList) {
                if (cwpBay.getBayNo().equals(bayNo)) {
                    craneNoList.add(craneNo);
                }
            }
        }
        if (craneNoList.size() == 1) {
            return craneNoList.get(0);
        } else {
//            cwpLogger.logInfo("AutoDelCraneMethod.getMaxCwpCraneNoInMaxCwpBayList: the max road is selected by " + craneNoList.size() + " cranes in last DP! It can't happen, except for the first DP or not selected in last DP!");
            return null;
        }
    }

    static List<CWPCrane> getSideCwpCraneList(String side, List<CWPCrane> cwpCranes, CWPCrane maxCwpCrane) {
        List<CWPCrane> cwpCraneList = new ArrayList<>();
        if (maxCwpCrane == null || cwpCranes.isEmpty()) {
            return cwpCraneList;
        }
        for (CWPCrane cwpCrane : cwpCranes) {
            if (side.equals(CWPDomain.LEFT)) {
                if (cwpCrane.getDpCurrentWorkPosition().compareTo(maxCwpCrane.getDpCurrentWorkPosition()) < 0) {
                    cwpCraneList.add(cwpCrane);
                }
            }
            if (side.equals(CWPDomain.RIGHT)) {
                if (cwpCrane.getDpCurrentWorkPosition().compareTo(maxCwpCrane.getDpCurrentWorkPosition()) > 0) {
                    cwpCraneList.add(cwpCrane);
                }
            }
        }
        return cwpCraneList;
    }

    static void analyzeMaxRoadCrane(CWPCrane maxCwpCrane, CWPData cwpData) {
        CWPConfiguration cwpConfiguration = cwpData.getVesselVisit().getCwpConfiguration();
        CWPBay cwpBayFrom = cwpData.getCWPBayByBayNo(maxCwpCrane.getDpWorkBayNoFrom());
        CWPBay cwpBayTo = cwpData.getCWPBayByBayNo(maxCwpCrane.getDpWorkBayNoTo());
        if (cwpBayFrom != null) {
            if (cwpBayFrom.isDividedBay() && maxCwpCrane.getDpWorkTimeFrom() > cwpConfiguration.getCraneMeanEfficiency()) {
                cwpBayFrom.setDividedBay(false);
            }
        }
        if (cwpBayTo != null) {
            if (cwpBayTo.isDividedBay() && maxCwpCrane.getDpWorkTimeTo() > cwpConfiguration.getCraneMeanEfficiency()) {
                cwpBayTo.setDividedBay(false);
            }
        }
    }

    static void analyzeMaxRoadCraneAndSideCrane(CWPCrane maxCwpCrane, CWPData cwpData) {
        CWPBay cwpBayFrom = cwpData.getCWPBayByBayNo(maxCwpCrane.getDpWorkBayNoFrom());
        CWPBay cwpBayTo = cwpData.getCWPBayByBayNo(maxCwpCrane.getDpWorkBayNoTo());
        if (cwpBayFrom != null) {
            //旁边的桥作业范围也要改变
            CWPCrane cwpCraneLeft = PublicMethod.getSideCWPCrane(CWPDomain.LEFT, maxCwpCrane.getCraneNo(), cwpData);
            if (cwpCraneLeft != null) {
                CWPBay cwpBayLeftTo = cwpData.getCWPBayByBayNo(cwpCraneLeft.getDpWorkBayNoTo());
                if (cwpBayLeftTo != null) {
                    if (cwpBayLeftTo.getWorkPosition().compareTo(cwpBayFrom.getWorkPosition()) >= 0) {
                        CWPBay cwpBayFromSide = PublicMethod.getSideCwpBay(CWPDomain.LEFT, cwpBayFrom.getBayNo(), cwpData);
                        cwpCraneLeft.setDpWorkPositionTo(cwpBayFromSide.getWorkPosition());
                        cwpCraneLeft.setDpWorkBayNoTo(cwpBayFromSide.getBayNo());
                        cwpCraneLeft.setDpWorkTimeTo(0L);
                    }
                }
            }
        }
        if (cwpBayTo != null) {
            CWPCrane cwpCraneRight = PublicMethod.getSideCWPCrane(CWPDomain.RIGHT, maxCwpCrane.getCraneNo(), cwpData);
            if (cwpCraneRight != null) {
                CWPBay cwpBayRightFrom = cwpData.getCWPBayByBayNo(cwpCraneRight.getDpWorkBayNoFrom());
                if (cwpBayRightFrom != null) {
                    if (cwpBayRightFrom.getWorkPosition().compareTo(cwpBayTo.getWorkPosition()) <= 0) {
                        CWPBay cwpBayToSide = PublicMethod.getSideCwpBay(CWPDomain.RIGHT, cwpBayTo.getBayNo(), cwpData);
                        cwpCraneRight.setDpWorkPositionTo(cwpBayToSide.getWorkPosition());
                        cwpCraneRight.setDpWorkBayNoTo(cwpBayToSide.getBayNo());
                        cwpCraneRight.setDpWorkTimeTo(0L);
                    }
                }
            }
        }
    }
}
