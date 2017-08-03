package com.cwp.single.cwp.cwpvessel;

import com.cwp.allvessel.manager.VesselVisit;
import com.cwp.entity.CWPBay;
import com.cwp.entity.CWPCrane;
import com.cwp.entity.CWPMachine;
import com.cwp.single.cwp.processorder.CWPHatch;

import java.util.*;

/**
 * Created by csw on 2017/4/24 13:47.
 * Explain: 算法流程用到的数据对象
 */
public class CWPData {

    private VesselVisit vesselVisit; //船舶访问信息

    private Map<Integer, CWPBay> cwpBayMap;
    private Map<String, CWPCrane> cwpCraneMap;
    private Map<Long, CWPHatch> cwpHatchMap;
    private Map<Double, CWPMachine> cwpMachineMap;//驾驶台、烟囱等机械信息<位置, 机械信息>

    private boolean isFirstRealWork;
    private boolean isDoWorkCwp;
    private Boolean firstDoWorkCwp;

    //最少、最多桥机数
    private Integer maxCraneNum;
    private Integer minCraneNum;

    private Long startWorkTime;
    private Long currentWorkTime;//cwp当前作业时间,全局时间
    private Long totalWorkTime;//该船所有作业时间量
    private Long curTotalWorkTime;//当前时刻，该船所有作业时间量

    private boolean isDelCraneNow;//是否现在可以减桥机
    private Long delCraneTime;//减桥机时间
    private Integer delCraneNum;//减几部桥机
    private boolean isAddCraneNow;//是否现在可以加桥机
    private Long addCraneTime;//加桥机时间
    private Integer addCraneNum;//加几部桥机

    private boolean hasCraneBreakdownCanMove;//是否有桥机发生故障，可以移动
    private boolean hasCraneBreakdownCanNotMove;//是否有桥机发生故障，不可以移动
    private boolean hasCraneCanWorkNow;//是否现在有桥机可以正常工作（如维修完毕）
    private boolean hasCraneCanNotWorkNow;//是否现在有桥机不能正常工作（如进入维修状态）

    private Boolean autoDelCraneNow; //CWP计算过程中发生自动减桥机标识

    public CWPData(VesselVisit vesselVisit) {
        this.vesselVisit = vesselVisit;
        cwpBayMap = new HashMap<>();
        cwpCraneMap = new HashMap<>();
        cwpHatchMap = new HashMap<>();
        cwpMachineMap = new HashMap<>();
        isDelCraneNow = false;
        isAddCraneNow = false;
        hasCraneBreakdownCanMove = false;
        hasCraneBreakdownCanNotMove = false;
        hasCraneCanWorkNow = false;
        hasCraneCanNotWorkNow = false;
        isFirstRealWork = true;
        isDoWorkCwp = false;
        firstDoWorkCwp = true;
        maxCraneNum = 0;
        minCraneNum = 0;
        totalWorkTime = 0L;
        curTotalWorkTime = 0L;
        autoDelCraneNow = false;
    }

    public Boolean getAutoDelCraneNow() {
        return autoDelCraneNow;
    }

    public void setAutoDelCraneNow(Boolean autoDelCraneNow) {
        this.autoDelCraneNow = autoDelCraneNow;
    }

    public boolean isFirstRealWork() {
        return isFirstRealWork;
    }

    public void setFirstRealWork(boolean firstRealWork) {
        isFirstRealWork = firstRealWork;
    }

    public boolean isDoWorkCwp() {
        return isDoWorkCwp;
    }

    public void setDoWorkCwp(boolean doWorkCwp) {
        isDoWorkCwp = doWorkCwp;
    }

    public Boolean getFirstDoWorkCwp() {
        return firstDoWorkCwp;
    }

    public void setFirstDoWorkCwp(Boolean firstDoWorkCwp) {
        this.firstDoWorkCwp = firstDoWorkCwp;
    }

    public VesselVisit getVesselVisit() {
        return this.vesselVisit;
    }

    public boolean isHasCraneBreakdownCanMove() {
        return hasCraneBreakdownCanMove;
    }

    public void setHasCraneBreakdownCanMove(boolean hasCraneBreakdownCanMove) {
        this.hasCraneBreakdownCanMove = hasCraneBreakdownCanMove;
    }

    public boolean isHasCraneBreakdownCanNotMove() {
        return hasCraneBreakdownCanNotMove;
    }

    public void setHasCraneBreakdownCanNotMove(boolean hasCraneBreakdownCanNotMove) {
        this.hasCraneBreakdownCanNotMove = hasCraneBreakdownCanNotMove;
    }

    public boolean isHasCraneCanWorkNow() {
        return hasCraneCanWorkNow;
    }

    public void setHasCraneCanWorkNow(boolean hasCraneCanWorkNow) {
        this.hasCraneCanWorkNow = hasCraneCanWorkNow;
    }

    public boolean isHasCraneCanNotWorkNow() {
        return hasCraneCanNotWorkNow;
    }

    public void setHasCraneCanNotWorkNow(boolean hasCraneCanNotWorkNow) {
        this.hasCraneCanNotWorkNow = hasCraneCanNotWorkNow;
    }

    public boolean isDelCraneNow() {
        return isDelCraneNow;
    }

    public void setDelCraneNow(boolean delCraneNow) {
        isDelCraneNow = delCraneNow;
    }

    public boolean isAddCraneNow() {
        return isAddCraneNow;
    }

    public void setAddCraneNow(boolean addCraneNow) {
        isAddCraneNow = addCraneNow;
    }

    public Long getDelCraneTime() {
        return delCraneTime;
    }

    public void setDelCraneTime(Long delCraneTime) {
        this.delCraneTime = delCraneTime;
    }

    public Integer getDelCraneNum() {
        return delCraneNum;
    }

    public void setDelCraneNum(Integer delCraneNum) {
        this.delCraneNum = delCraneNum;
    }

    public Long getAddCraneTime() {
        return addCraneTime;
    }

    public void setAddCraneTime(Long addCraneTime) {
        this.addCraneTime = addCraneTime;
    }

    public Integer getAddCraneNum() {
        return addCraneNum;
    }

    public void setAddCraneNum(Integer addCraneNum) {
        this.addCraneNum = addCraneNum;
    }

    public Long getStartWorkTime() {
        return startWorkTime;
    }

    public void setStartWorkTime(Long startWorkTime) {
        this.startWorkTime = startWorkTime;
    }

    public void addCurrentWorkTime(Long currentWorkTime) {
        this.currentWorkTime += currentWorkTime;
    }

    public Long getCurrentWorkTime() {
        return currentWorkTime;
    }

    public void setCurrentWorkTime(Long currentWorkTime) {
        this.currentWorkTime = currentWorkTime;
    }

    public Long getTotalWorkTime() {
        return totalWorkTime;
    }

    public void setTotalWorkTime(Long totalWorkTime) {
        this.totalWorkTime = totalWorkTime;
    }

    public void addTotalWorkTime(Long totalWorkTime) {
        this.totalWorkTime += totalWorkTime;
    }

    public Long getCurTotalWorkTime() {
        return curTotalWorkTime;
    }

    public void setCurTotalWorkTime(Long curTotalWorkTime) {
        this.curTotalWorkTime = curTotalWorkTime;
    }

    public void addCurTotalWorkTime(Long curTotalWorkTime) {
        this.curTotalWorkTime += curTotalWorkTime;
    }

    public Integer getMaxCraneNum() {
        return maxCraneNum;
    }

    public void setMaxCraneNum(Integer maxCraneNum) {
        this.maxCraneNum = maxCraneNum;
    }

    public Integer getMinCraneNum() {
        return minCraneNum;
    }

    public void setMinCraneNum(Integer minCraneNum) {
        this.minCraneNum = minCraneNum;
    }


    public CWPBay getCWPBayByBayNo(Integer bayNo) {
        return this.cwpBayMap.get(bayNo);
    }

    public CWPCrane getCWPCraneByCraneNo(String craneNo) {
        return this.cwpCraneMap.get(craneNo);
    }

    public List<CWPCrane> getAllCranes() {
        List<CWPCrane> cwpCraneList = new ArrayList<>(cwpCraneMap.values());
        Collections.sort(cwpCraneList, new Comparator<CWPCrane>() {
            @Override
            public int compare(CWPCrane o1, CWPCrane o2) {
                if (o1.getCraneSeq() != null && o2.getCraneSeq() != null) {
                    return o1.getCraneSeq().compareTo(o2.getCraneSeq());
                } else {
                    if (o1.getDpCurrentWorkPosition().equals(o2.getDpCurrentWorkPosition())) {
                        return o1.getCraneNo().compareTo(o2.getCraneNo());
                    } else {
                        return o1.getDpCurrentWorkPosition().compareTo(o2.getDpCurrentWorkPosition());
                    }
                }
            }
        });
        return cwpCraneList;
    }

    public List<CWPBay> getAllBays() {
        List<CWPBay> cwpBayList = new ArrayList<>(cwpBayMap.values());
        Collections.sort(cwpBayList, new Comparator<CWPBay>() {
            @Override
            public int compare(CWPBay o1, CWPBay o2) {
                return o1.getWorkPosition().compareTo(o2.getWorkPosition());
            }
        });
        return cwpBayList;
    }

    public void addCWPCrane(CWPCrane cwpCrane) {
        this.cwpCraneMap.put(cwpCrane.getCraneNo(), cwpCrane);
    }

    public void removeCWPCrane(CWPCrane cwpCrane) {
        this.cwpCraneMap.remove(cwpCrane.getCraneNo());
    }

    public void addCWPBay(CWPBay cwpBay) {
        this.cwpBayMap.put(cwpBay.getBayNo(), cwpBay);
    }

    public List<CWPHatch> getAllHatches() {
        return new ArrayList<>(cwpHatchMap.values());
    }

    public CWPHatch getCWPHatchByHatchId(Long hatchId) {
        return this.cwpHatchMap.get(hatchId);
    }

    public void addCWPHatch(CWPHatch cwpHatch) {
        this.cwpHatchMap.put(cwpHatch.getHatchId(), cwpHatch);
    }

    public void addCWPMachine(CWPMachine cwpMachine) {
        this.cwpMachineMap.put(cwpMachine.getMachinePosition(), cwpMachine);
    }

    public List<CWPMachine> getAllMachines() {
        return new ArrayList<>(this.cwpMachineMap.values());
    }

    public Integer getNextBayNo(Integer bayNo) {
        List<CWPBay> cwpBayList = this.getAllBays();
        for (int j = 0; j < cwpBayList.size(); j++) {
            if (cwpBayList.get(j).getBayNo().equals(bayNo)) {
                if (j + 1 < cwpBayList.size()) {
                    return cwpBayList.get(j + 1).getBayNo();
                }
            }
        }
        return bayNo;
    }

    public Integer getFrontBayNo(Integer bayNo) {
        List<CWPBay> cwpBayList = this.getAllBays();
        for (int j = 0; j < cwpBayList.size(); j++) {
            if (cwpBayList.get(j).getBayNo().equals(bayNo)) {
                if (j - 1 >= 0) {
                    return cwpBayList.get(j - 1).getBayNo();
                }
            }
        }
        return bayNo;
    }

    public CWPCrane getNextCWPCrane(String craneNo) {
        List<CWPCrane> cwpCraneList = this.getAllCranes();
        for (int i = 0; i < cwpCraneList.size(); i++) {
            if (cwpCraneList.get(i).getCraneNo().equals(craneNo)) {
                if (i + 1 < cwpCraneList.size()) {
                    return cwpCraneList.get(i + 1);
                }
            }
        }
        return null;
    }

    public CWPCrane getFrontCWPCrane(String craneNo) {
        List<CWPCrane> cwpCraneList = this.getAllCranes();
        for (int i = 0; i < cwpCraneList.size(); i++) {
            if (cwpCraneList.get(i).getCraneNo().equals(craneNo)) {
                if (i - 1 >= 0) {
                    return cwpCraneList.get(i - 1);
                }
            }
        }
        return null;
    }

    public boolean isLastBayNo(Integer bayNo) {
        List<CWPBay> cwpBayList = this.getAllBays();
        return cwpBayList.size() > 0 && cwpBayList.get(cwpBayList.size() - 1).getBayNo().equals(bayNo);
    }

    public boolean isFirstBayNo(Integer bayNo) {
        List<CWPBay> cwpBayList = this.getAllBays();
        return cwpBayList.size() > 0 && cwpBayList.get(0).getBayNo().equals(bayNo);
    }
}
