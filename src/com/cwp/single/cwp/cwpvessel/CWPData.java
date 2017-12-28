package com.cwp.single.cwp.cwpvessel;

import com.cwp.allvessel.manager.VesselVisit;
import com.cwp.entity.CWPBay;
import com.cwp.entity.CWPConfiguration;
import com.cwp.entity.CWPCrane;
import com.cwp.entity.CWPMachine;
import com.cwp.single.cwp.dp.DPCraneSelectBay;
import com.cwp.single.cwp.dp.DPResult;
import com.cwp.single.cwp.process.MethodParameter;
import com.cwp.single.cwp.processorder.CWPHatch;
import com.sun.org.apache.xpath.internal.operations.Bool;

import javax.swing.text.StyledEditorKit;
import java.util.*;

/**
 * Created by csw on 2017/4/24 13:47.
 * Explain: 算法流程用到的数据对象
 */
public class CWPData {

    private VesselVisit vesselVisit; //船舶访问信息
    private CWPConfiguration cwpConfiguration;

    private Map<Integer, CWPBay> cwpBayMap;
    private Map<String, CWPCrane> cwpCraneMap;
    private Map<Long, CWPHatch> cwpHatchMap;
    private Map<Double, CWPMachine> cwpMachineMap;//驾驶台、烟囱等机械信息<位置, 机械信息>

    private DPResult dpResult;
    private List<DPCraneSelectBay> dpCraneSelectBays;
    private MethodParameter methodParameter;

    private Boolean doWorkCwp;
    private Boolean doPlanCwp;
    private Boolean firstDoCwp;

    //最少、最多桥机数
    private Integer initMaxCraneNum;
    private Integer initMinCraneNum;

    private Long cwpStartTime; //开始排计划的初始时间
    private Long cwpCurrentTime;//cwp当前作业时间,全局时间

    private Boolean craneBreakdownNow;//是否现在有桥机发生故障
    private Boolean craneCanWorkNow;//是否现在有桥机可以正常工作（如维修完毕）
    private Boolean craneCanNotWorkNow;//是否现在有桥机不能正常工作（如进入维修状态）

    private Boolean leftDivide;
    private Boolean rightDivide;

    public CWPData(VesselVisit vesselVisit) {
        this.vesselVisit = vesselVisit;
        cwpConfiguration = vesselVisit.getCwpConfiguration();
        cwpBayMap = new HashMap<>();
        cwpCraneMap = new HashMap<>();
        cwpHatchMap = new HashMap<>();
        cwpMachineMap = new HashMap<>();
        dpResult = new DPResult();
        dpCraneSelectBays = new ArrayList<>();
        methodParameter = new MethodParameter();
        craneBreakdownNow = false;
        craneCanWorkNow = false;
        craneCanNotWorkNow = false;
        leftDivide = true;
        rightDivide = true;
    }

    public Boolean getLeftDivide() {
        return leftDivide;
    }

    public void setLeftDivide(Boolean leftDivide) {
        this.leftDivide = leftDivide;
    }

    public Boolean getRightDivide() {
        return rightDivide;
    }

    public void setRightDivide(Boolean rightDivide) {
        this.rightDivide = rightDivide;
    }

    public CWPConfiguration getCwpConfiguration() {
        return cwpConfiguration;
    }

    public DPResult getDpResult() {
        return dpResult;
    }

    public List<DPCraneSelectBay> getDpCraneSelectBays() {
        return dpCraneSelectBays;
    }

    public MethodParameter getMethodParameter() {
        return methodParameter;
    }

    public Boolean getDoWorkCwp() {
        return doWorkCwp;
    }

    public void setDoWorkCwp(Boolean doWorkCwp) {
        this.doWorkCwp = doWorkCwp;
    }

    public Boolean getDoPlanCwp() {
        return doPlanCwp;
    }

    public void setDoPlanCwp(Boolean doPlanCwp) {
        this.doPlanCwp = doPlanCwp;
    }

    public Boolean getFirstDoCwp() {
        return firstDoCwp;
    }

    public void setFirstDoCwp(Boolean firstDoCwp) {
        this.firstDoCwp = firstDoCwp;
    }

    public Integer getInitMaxCraneNum() {
        return initMaxCraneNum;
    }

    public void setInitMaxCraneNum(Integer initMaxCraneNum) {
        this.initMaxCraneNum = initMaxCraneNum;
    }

    public Integer getInitMinCraneNum() {
        return initMinCraneNum;
    }

    public void setInitMinCraneNum(Integer initMinCraneNum) {
        this.initMinCraneNum = initMinCraneNum;
    }

    public VesselVisit getVesselVisit() {
        return this.vesselVisit;
    }

    public Boolean getCraneBreakdownNow() {
        return craneBreakdownNow;
    }

    public void setCraneBreakdownNow(Boolean craneBreakdownNow) {
        this.craneBreakdownNow = craneBreakdownNow;
    }

    public Boolean getCraneCanWorkNow() {
        return craneCanWorkNow;
    }

    public void setCraneCanWorkNow(Boolean craneCanWorkNow) {
        this.craneCanWorkNow = craneCanWorkNow;
    }

    public Boolean getCraneCanNotWorkNow() {
        return craneCanNotWorkNow;
    }

    public void setCraneCanNotWorkNow(Boolean craneCanNotWorkNow) {
        this.craneCanNotWorkNow = craneCanNotWorkNow;
    }

    public Long getCwpStartTime() {
        return cwpStartTime;
    }

    public void setCwpStartTime(Long cwpStartTime) {
        this.cwpStartTime = cwpStartTime;
    }

    public Long getCwpCurrentTime() {
        return cwpCurrentTime;
    }

    public void setCwpCurrentTime(Long cwpCurrentTime) {
        this.cwpCurrentTime = cwpCurrentTime;
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

}
