package com.cwp.single.cwp.dp;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by csw on 2017/3/8 19:42.
 * Explain: DP过程用于决策的作业时间量
 */
public class DPCraneSelectBay implements Serializable {

    private DPPair dpPair;
    private Long dpWorkTime;
    private Double dpDistance;
    private boolean isTroughMachine;
    private Long dpWorkTimeToDpBefore;
    private Long dpWorkTimeToDpAfter;

    public DPCraneSelectBay(DPPair dpPair) {
        this.dpPair = dpPair;
        this.dpWorkTime = 0L;
        this.dpWorkTimeToDpBefore = 0L;
        this.dpWorkTimeToDpAfter = 0L;
        this.dpDistance = Double.MAX_VALUE;
        this.isTroughMachine = false;
    }

    public Long getDpWorkTimeToDpAfter() {
        return dpWorkTimeToDpAfter;
    }

    public void setDpWorkTimeToDpAfter(Long dpWorkTimeToDpAfter) {
        this.dpWorkTimeToDpAfter = dpWorkTimeToDpAfter;
    }

    public Long getDpWorkTimeToDpBefore() {
        return dpWorkTimeToDpBefore;
    }

    public void setDpWorkTimeToDpBefore(Long dpWorkTimeToDpBefore) {
        this.dpWorkTimeToDpBefore = dpWorkTimeToDpBefore;
    }

    public Long getDpWorkTime() {
        return dpWorkTime;
    }

    public void setDpWorkTime(Long dpWorkTime) {
        this.dpWorkTime = dpWorkTime;
    }

    public void addDpWorkTime(Long dpWorkTime) {
        this.dpWorkTime += dpWorkTime;
    }

    public Double getDpDistance() {
        return dpDistance;
    }

    public void setDpDistance(Double dpDistance) {
        this.dpDistance = dpDistance;
    }

    public boolean isTroughMachine() {
        return isTroughMachine;
    }

    public void setTroughMachine(boolean troughMachine) {
        isTroughMachine = troughMachine;
    }

    public DPPair getDpPair() {
        return dpPair;
    }

    private boolean equalsWithPair(Object obj) {
        return this.dpPair.getFirst().equals(((DPPair) obj).getFirst())
                && this.dpPair.getSecond().equals(((DPPair) obj).getSecond());
    }

    public static DPCraneSelectBay getDpCraneSelectBayByPair(List<DPCraneSelectBay> dpCraneSelectBays, DPPair DPPair) {
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            if (dpCraneSelectBay.equalsWithPair(DPPair)) {
                return dpCraneSelectBay;
            }
        }
        return null;
    }

    public static List<DPCraneSelectBay> getDpCraneSelectBaysByCrane(List<DPCraneSelectBay> dpCraneSelectBays, String craneNo) {
        List<DPCraneSelectBay> dpCraneSelectBayList = new ArrayList<>();
        for (DPCraneSelectBay dpCraneSelectBay : dpCraneSelectBays) {
            if (dpCraneSelectBay.getDpPair().getFirst().equals(craneNo)) {
                dpCraneSelectBayList.add(dpCraneSelectBay);
            }
        }
        return dpCraneSelectBayList;
    }

    public DPCraneSelectBay deepCopy() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);

            oos.writeObject(this);

            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            ObjectInputStream ois = new ObjectInputStream(bis);

            return (DPCraneSelectBay) ois.readObject();

        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

}
