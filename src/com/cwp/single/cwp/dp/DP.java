package com.cwp.single.cwp.dp;

import com.cwp.config.CWPDefaultValue;
import com.cwp.entity.CWPBay;
import com.cwp.entity.CWPCrane;
import com.cwp.utils.LogPrinter;

import java.util.List;

/**
 * Created by csw on 2017/4/19 23:05.
 * Explain:
 */
public class DP {

    public DPResult cwpKernel(List<CWPCrane> cwpCranes, List<CWPBay> cwpBays, List<DPCraneSelectBay> dpCraneSelectBays) {

        int craneNum = cwpCranes.size();
        int bayNum = cwpBays.size();
        int dpSize = dpCraneSelectBays.size();
        if (craneNum == 0 || bayNum == 0 || dpSize == 0) {
            return new DPResult();
        }

        DPResult[][] dp = new DPResult[craneNum][bayNum];
        for (int i = 0; i < craneNum; i++) {
            for (int j = 0; j < bayNum; j++) {
                dp[i][j] = new DPResult();
            }
        }

        CWPCrane cwpCrane0 = cwpCranes.get(0);
        CWPBay cwpBay0 = cwpBays.get(0);
        DPPair dpPair0 = new DPPair<>(cwpCrane0.getCraneNo(), cwpBay0.getBayNo());
        DPCraneSelectBay dpCraneSelectBay0 = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair0);
        if (dpCraneSelectBay0 != null) {//it can't be null
            dpCraneSelectBay0.getDpPair().setCranePosition(cwpBay0.getWorkPosition());
            if (dpCraneSelectBay0.getDpWorkTime() > 0) {
                dp[0][0].setDpWorkTime(dpCraneSelectBay0.getDpWorkTime());
                dp[0][0].setDpDistance(dpCraneSelectBay0.getDpDistance());
                dp[0][0].getDpTraceBack().add(dpCraneSelectBay0.getDpPair());
                dp[0][0].getDpCranePositions().add(dpCraneSelectBay0.getDpPair());
            } else {
                dp[0][0].setDpDistance(dpCraneSelectBay0.getDpDistance());
                dp[0][0].getDpCranePositions().add(dpCraneSelectBay0.getDpPair());
            }
        }
        for (int i = 1; i < craneNum; i++) {
            CWPCrane cwpCrane = cwpCranes.get(i);
            DPPair dpPair = new DPPair<>(cwpCrane.getCraneNo(), cwpBay0.getBayNo());
            DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
            if (dpCraneSelectBay != null) {//it can't be null
                if (dpCraneSelectBay.getDpWorkTime() > 0) {
                    dp[i][0].setDpWorkTime(dpCraneSelectBay.getDpWorkTime());
                    dp[i][0].setDpDistance(dpCraneSelectBay.getDpDistance());
                    dp[i][0].getDpTraceBack().add(dpCraneSelectBay.getDpPair());
                    dpCraneSelectBay.getDpPair().setCranePosition(cwpBay0.getWorkPosition());
                    dp[i][0].getDpCranePositions().add(dpCraneSelectBay.getDpPair());
                } else {
                    dp[i][0].setDpDistance(dpCraneSelectBay.getDpDistance()); //？
                    dpCraneSelectBay.getDpPair().setCranePosition(cwpBay0.getWorkPosition()); //position of the crane???
                    dp[i][0].getDpCranePositions().add(dpCraneSelectBay.getDpPair());
                }
//                dp[i][0].setDpWorkTime(dpCraneSelectBay.getDpWorkTime());
//                dp[i][0].setDpDistance(dpCraneSelectBay.getDpDistance());
//                dp[i][0].getDpTraceBack().add(dpCraneSelectBay.getDpPair());
//                dpCraneSelectBay.getDpPair().setCranePosition(cwpBay0.getWorkPosition());
//                dp[i][0].getDpCranePositions().add(dpCraneSelectBay.getDpPair());
            }
        }
        for (int j = 1; j < bayNum; j++) {
            CWPBay cwpBay = cwpBays.get(j);
            DPPair dpPair = new DPPair<>(cwpCrane0.getCraneNo(), cwpBay.getBayNo());
            DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
            if (dpCraneSelectBay != null) {//it can't be null
                dp[0][j].setDpWorkTime(dpCraneSelectBay.getDpWorkTime());
                dp[0][j].setDpDistance(dpCraneSelectBay.getDpDistance());
                if (better(dp[0][j], dp[0][j - 1])) {
                    if (dpCraneSelectBay.getDpWorkTime() > 0) {
                        dp[0][j].getDpTraceBack().add(dpCraneSelectBay.getDpPair());
                    }
                    dpCraneSelectBay.getDpPair().setCranePosition(cwpBay.getWorkPosition());
                    dp[0][j].getDpCranePositions().add(dpCraneSelectBay.getDpPair());
                } else {
                    dp[0][j] = dp[0][j - 1].deepCopy();
                }
            }
        }

        for (int i = 1; i < craneNum; i++) {
            for (int j = 1; j < bayNum; j++) {
                CWPCrane cwpCrane = cwpCranes.get(i);
                CWPBay cwpBay = cwpBays.get(j);
                DPPair dpPair = new DPPair<>(cwpCrane.getCraneNo(), cwpBay.getBayNo());
                DPCraneSelectBay dpCraneSelectBay = DPCraneSelectBay.getDpCraneSelectBayByPair(dpCraneSelectBays, dpPair);
                if (dpCraneSelectBay != null) {//it can't be null
                    if (dpCraneSelectBay.getDpWorkTime() > 0 || unusableCrane(cwpCrane) || i == craneNum - 1) { //the last crane???
                        DPResult cur_dp = new DPResult();
                        int k = j;
                        while (k >= 0 && cwpBay.getWorkPosition() - cwpBays.get(k).getWorkPosition() < 2 * CWPDefaultValue.craneSafeSpan) {
                            k--;
                        }
                        if (k < 0) {
                            cur_dp.setDpWorkTime(dpCraneSelectBay.getDpWorkTime());
                            cur_dp.setDpDistance(dpCraneSelectBay.getDpDistance());
                        } else {
                            cur_dp.setDpWorkTime(dpCraneSelectBay.getDpWorkTime() + dp[i - 1][k].getDpWorkTime());
                            double d = dp[i - 1][k].getDpWorkTime() > 0 ? dp[i - 1][k].getDpDistance() : 0;
                            cur_dp.setDpDistance(dpCraneSelectBay.getDpDistance() + d); //???
//                            cur_dp.setDpDistance(dpCraneSelectBay.getDpDistance() + dp[i - 1][k].getDpDistance());
                            cur_dp.setDpTraceBack(dp[i - 1][k].getDpTraceBack());
                            cur_dp.setDpCranePositions(dp[i - 1][k].getDpCranePositions());
                        }
                        if (better(cur_dp, dp[i][j - 1])) {
                            dp[i][j] = cur_dp.deepCopy();
                            dpCraneSelectBay.getDpPair().setCranePosition(cwpBay.getWorkPosition());
                            dp[i][j].getDpCranePositions().add(dpCraneSelectBay.getDpPair());
                            if (dpCraneSelectBay.getDpWorkTime() > 0) {
                                dp[i][j].getDpTraceBack().add(dpCraneSelectBay.getDpPair());
                            }
                        } else {
                            dp[i][j] = dp[i][j - 1].deepCopy();
                        }
                    } else { //????????
                        dp[i][j] = dp[i][j - 1].deepCopy();
                    }
//                    DPResult cur_dp = new DPResult();
//                    int k = j;
//                    while (k >= 0 && cwpBay.getWorkPosition() - cwpBays.get(k).getWorkPosition() < 2 * CWPDefaultValue.craneSafeSpan) {
//                        k--;
//                    }
//                    if (k < 0) {
//                        cur_dp.setDpWorkTime(dpCraneSelectBay.getDpWorkTime());
//                        cur_dp.setDpDistance(dpCraneSelectBay.getDpDistance());
//                    } else {
//                        cur_dp.setDpWorkTime(dpCraneSelectBay.getDpWorkTime() + dp[i - 1][k].getDpWorkTime());
//                        cur_dp.setDpDistance(dpCraneSelectBay.getDpDistance() + dp[i - 1][k].getDpDistance());
//                        cur_dp.setDpTraceBack(dp[i - 1][k].getDpTraceBack());
//                        cur_dp.setDpCranePositions(dp[i - 1][k].getDpCranePositions());
//                    }
//                    if (better(cur_dp, dp[i][j - 1])) {
//                        dp[i][j] = cur_dp.deepCopy();
//                        dpCraneSelectBay.getDpPair().setCranePosition(cwpBay.getWorkPosition());
//                        dp[i][j].getDpCranePositions().add(dpCraneSelectBay.getDpPair());
//                        if (dpCraneSelectBay.getDpWorkTime() > 0) {
//                            dp[i][j].getDpTraceBack().add(dpCraneSelectBay.getDpPair());
//                        }
//                    } else {
//                        dp[i][j] = dp[i][j - 1].deepCopy();
//                    }
                }
            }
        }

        DPResult dpResult = dp[craneNum - 1][bayNum - 1].deepCopy();
        for (CWPCrane cwpCrane : cwpCranes) {
            for (DPPair dpPair : dpResult.getDpCranePositions()) {
                if (cwpCrane.getCraneNo().equals(dpPair.getFirst())) {
                    cwpCrane.setDpCurrentWorkBayNo((Integer) dpPair.getSecond());
                    cwpCrane.setDpCurrentWorkPosition(dpPair.getCranePosition());
                }
            }
        }

        //log
        LogPrinter.printDpInfo(cwpCranes, cwpBays, dp);

        return dpResult;
    }

    private boolean unusableCrane(CWPCrane cwpCrane) {
        return cwpCrane.isThroughMachineNow() || cwpCrane.isMaintainNow() || cwpCrane.isBreakdown() || cwpCrane.getWorkDone();
    }

    private boolean better(DPResult cur_dp, DPResult dpResult) {
        return cur_dp.getDpWorkTime() > dpResult.getDpWorkTime() || (cur_dp.getDpWorkTime().longValue() == dpResult.getDpWorkTime().longValue() && cur_dp.getDpDistance() < dpResult.getDpDistance());
    }
}
