package com.cwp.allvessel.manager;

import com.cwp.config.CWPDefaultValue;
import com.cwp.config.CWPDomain;
import com.cwp.entity.*;
import com.cwp.entity.CWPConfiguration;
import com.cwp.log.CWPLogger;
import com.cwp.log.CWPLoggerFactory;
import com.cwp.single.cwp.process.CWPProcess;
import com.cwp.single.mo.process.MOProcess;
import com.cwp.utils.BeanCopy;
import com.cwp.utils.Validator;
import com.shbtos.biz.smart.cwp.pojo.*;
import com.shbtos.biz.smart.cwp.service.SmartCwpImportData;

/**
 * Created by csw on 2017/4/19 22:59.
 * Explain:
 */
public class VesselVisitManager {

    CWPLogger cwpLogger = CWPLoggerFactory.getCWPLogger();

    public CWPSchedule buildCWPSchedule(VesselVisit vesselVisit, SmartCwpImportData smartCwpImportData) {
        Validator.notNull("船舶访问信息VesselVisit为null", vesselVisit);
        Long berthId = vesselVisit.getBerthId();
        cwpLogger.logInfo("开始创建船舶船期信息, berthId: " + berthId);
        CWPSchedule cwpSchedule = null;
        for (SmartScheduleIdInfo smartScheduleIdInfo : smartCwpImportData.getSmartScheduleIdInfoList()) {
            if (smartScheduleIdInfo.getBerthId().equals(berthId)) {
                cwpSchedule = new CWPSchedule(berthId);
                cwpSchedule = (CWPSchedule) BeanCopy.copyBean(smartScheduleIdInfo, cwpSchedule);
            }
        }
        return cwpSchedule;
    }

    public VesselVisit buildVesselVisit(VesselVisit vesselVisit, SmartCwpImportData smartCwpImportData) {
        Validator.notNull("船舶访问信息VesselVisit为null", vesselVisit);
        Long berthId = vesselVisit.getBerthId();
        cwpLogger.logInfo("开始创建船舶访问详细信息, berthId: " + berthId);
        try {
            //读取进出口船图箱信息
            Validator.listNotEmpty("缺少进出口船图箱数据", smartCwpImportData.getSmartVesselContainerInfoList());
            for (SmartVesselContainerInfo smartVesselContainerInfo : smartCwpImportData.getSmartVesselContainerInfoList()) {
                if (smartVesselContainerInfo.getBerthId().equals(berthId)) {
                    Long vpcCntrId = smartVesselContainerInfo.getVpcCntrId();
                    String vLocation = smartVesselContainerInfo.getvLocation();
                    String size = smartVesselContainerInfo.getcSzCsizecd();
                    String type = smartVesselContainerInfo.getcTypeCd();
                    String dlType = smartVesselContainerInfo.getLduldfg();
                    String throughFlag = smartVesselContainerInfo.getThroughFlag();
                    Long hatchId = smartVesselContainerInfo.getHatchId();
                    String workFlow = smartVesselContainerInfo.getWorkflow();
                    Long moveOrder = smartVesselContainerInfo.getCwpwkMoveNum();
                    Long cntWorkTime = smartVesselContainerInfo.getContainerWorkInterval();//单位秒
                    String cntHeight = smartVesselContainerInfo.getCntHeightDesc();//箱子具体高度
                    Double weight = smartVesselContainerInfo.getWeight();
                    String recycleWiFlag = smartVesselContainerInfo.getRecycleWiFlag();
                    String craneNo = smartVesselContainerInfo.getCraneNo();
                    throughFlag = "N".equals(throughFlag) ? CWPDomain.THROUGH_NO : CWPDomain.THROUGH_YES;
                    String directCntFlag = "Y".equals(smartVesselContainerInfo.getDirectCntFlag()) ? CWPDomain.Y : CWPDomain.N;
                    MOContainer moContainer = new MOContainer(vLocation, type, size, dlType);
                    if (CWPDomain.THROUGH_NO.equals(throughFlag)) { //非过境箱
                        moContainer.setThroughFlag(throughFlag);
                        moContainer.setBerthId(berthId);
                        moContainer.setHatchId(hatchId);
                        moContainer.setCntHeight(cntHeight);
                        moContainer.setCntWorkTime(cntWorkTime);
                        moContainer.setWeightKg(weight);
                        moContainer.setVpcCntrId(vpcCntrId);
                        moContainer.setRecycleWiFlag(recycleWiFlag);
                        moContainer.setCraneNo(craneNo);
                        moContainer.setWorkingStartTime(smartVesselContainerInfo.getWorkingStartTime());
                        moContainer.setWorkingEndTime(smartVesselContainerInfo.getWorkingEndTime());
                        moContainer.setDirectCntFlag(directCntFlag);
                        String manualFlag = smartVesselContainerInfo.getManualFlag();
                        String cwoManualWorkflow = smartVesselContainerInfo.getCwoManualWorkflow();
                        if ("Y".equals(manualFlag) || "Y".equals(cwoManualWorkflow)) { //人工指定工艺
                            moContainer.setWorkFlow(workFlow);
                        }
                        //重排的时候，锁定箱子的计划要排在最后
                        String cwoManualWi = smartVesselContainerInfo.getCwoManualWi();
                        if ("Y".equals(cwoManualWi)) {
                            CWPStowageLockLocation cwpStowageLockLocation = new CWPStowageLockLocation();
                            cwpStowageLockLocation.setHatchId(hatchId);
                            cwpStowageLockLocation.setvLocation(vLocation);
                            cwpStowageLockLocation.setLduldfg(dlType);
                            vesselVisit.addCWPStowageLockLocation(cwpStowageLockLocation);
                        }
                        try {
                            MOSlotPosition moSlotPosition = new MOSlotPosition(vLocation);
                            vesselVisit.putMOContainer(moSlotPosition, moContainer);
                        } catch (Exception e) {
                            e.printStackTrace();
                            cwpLogger.logError("输入数据不正确: 解析船箱位(" + vLocation + ")错误");
                        }
                    }
                }
            }
            //读取锁定船箱位信息
            for (SmartStowageLockLocationsInfo smartStowageLockLocationsInfo : smartCwpImportData.getSmartStowageLockLocationsInfoList()) {
                if (smartStowageLockLocationsInfo.getBerthId().equals(berthId)) {
                    if ("L".equals(smartStowageLockLocationsInfo.getLduldfg()) && smartStowageLockLocationsInfo.getvLocation() != null) {
                        CWPStowageLockLocation cwpStowageLockLocation = new CWPStowageLockLocation();
                        cwpStowageLockLocation.setHatchId(smartStowageLockLocationsInfo.getHatchId());
                        cwpStowageLockLocation.setvLocation(smartStowageLockLocationsInfo.getvLocation());
                        cwpStowageLockLocation.setLduldfg(smartStowageLockLocationsInfo.getLduldfg());
                        vesselVisit.addCWPStowageLockLocation(cwpStowageLockLocation);
                    }
                }
            }
            //读取每个舱的作业工艺
            for (SmartCraneWorkFlowInfo smartCraneWorkFlowInfo : smartCwpImportData.getSmartCraneWorkFlowInfoList()) {
                if (smartCraneWorkFlowInfo.getBerthId().equals(berthId)) {
                    Long hatchId = smartCraneWorkFlowInfo.getHatchId();
                    String ldStrategy = smartCraneWorkFlowInfo.getLdStrategy();
                    ldStrategy = ldStrategy != null ? ldStrategy.equals("LD") ? CWPDomain.LD_STRATEGY_LD : CWPDomain.LD_STRATEGY_BLD : CWPDomain.LD_STRATEGY_BLD;
                    vesselVisit.addHatchLdStrategy(hatchId, ldStrategy);
                    if (smartCraneWorkFlowInfo.getSingle()) {
                        vesselVisit.addWorkFlow(hatchId, "1");
                    }
                    if (smartCraneWorkFlowInfo.getTwin()) {
                        vesselVisit.addWorkFlow(hatchId, "2");
                    }
                    if (smartCraneWorkFlowInfo.getTandem()) {
                        vesselVisit.addWorkFlow(hatchId, "3");
                    }
                }
            }
            //读取桥机池、桥机信息
            Validator.listNotEmpty("输入数据中缺少船舶桥机池信息", smartCwpImportData.getSmartVesselCranePoolInfoList());
            boolean hasVesselCranePool = false;
            for (SmartVesselCranePoolInfo smartVesselCranePoolInfo : smartCwpImportData.getSmartVesselCranePoolInfoList()) {
                if (smartVesselCranePoolInfo.getBerthId().equals(berthId)) {
                    hasVesselCranePool = true;
                    CWPCraneVesselPool cwpCraneVesselPool = new CWPCraneVesselPool();
                    cwpCraneVesselPool = (CWPCraneVesselPool) BeanCopy.copyBean(smartVesselCranePoolInfo, cwpCraneVesselPool);
                    vesselVisit.setCWPCraneVesselPool(cwpCraneVesselPool);
                    break;
                }
            }
            if (hasVesselCranePool) {//有船舶桥机池
                Validator.listNotEmpty("桥机池中没有桥机信息", smartCwpImportData.getSmartCranePoolInfoList());
                CWPCraneVesselPool cwpCraneVesselPool = vesselVisit.getCWPCraneVesselPool();
                boolean hasCrane = false;
                for (SmartCranePoolInfo smartCranePoolInfo : smartCwpImportData.getSmartCranePoolInfoList()) {
                    if (smartCranePoolInfo.getPoolId() != null) {
                        if (smartCranePoolInfo.getPoolId().equals(cwpCraneVesselPool.getPoolId())) {
                            hasCrane = true;
                            CWPCranePool cwpCranePool = new CWPCranePool();
                            cwpCranePool = (CWPCranePool) BeanCopy.copyBean(smartCranePoolInfo, cwpCranePool);
                            vesselVisit.addCWPCranePool(cwpCranePool);
                            Validator.listNotEmpty("桥机基础数据信息没有", smartCwpImportData.getSmartCraneBaseInfoList());
                            for (SmartCraneBaseInfo smartCraneBaseInfo : smartCwpImportData.getSmartCraneBaseInfoList()) {
                                if (smartCraneBaseInfo.getCraneNo().equals(cwpCranePool.getCraneNo())) {
                                    CWPCrane cwpCrane = new CWPCrane(smartCraneBaseInfo.getCraneNo());
                                    cwpCrane = (CWPCrane) BeanCopy.copyBean(smartCraneBaseInfo, cwpCrane);
                                    vesselVisit.addCWPCrane(cwpCrane);
                                    cwpLogger.logInfo("桥机(No:" + cwpCranePool.getCraneNo() + ")作为安排该船舶CWP的桥机");
                                }
                            }
                        }
                    }
                }
                if (!hasCrane) {
                    cwpLogger.logError("船舶(berthId:" + berthId + ")虽然设置了桥机池，但是桥机池中没有相应的桥机！");
                }
            } else {
                cwpLogger.logError("船舶(berthId:" + berthId + ")的桥机池信息没有！");
            }
            //读取桥机作业计划信息
//            Validator.listNotEmpty("桥机开工计划信息没有", smartCwpImportData.getSmartCranePlanInfoList());
            for (SmartCranePlanInfo smartCranePlanInfo : smartCwpImportData.getSmartCranePlanInfoList()) {
                if (smartCranePlanInfo.getBerthId().equals(berthId)) {
                    CWPCranePlan cwpCranePlan = new CWPCranePlan();
                    cwpCranePlan = (CWPCranePlan) BeanCopy.copyBean(smartCranePlanInfo, cwpCranePlan);
                    vesselVisit.addCWPCranePlan(cwpCranePlan);
                }
            }
            //读取桥机维修计划信息
            if (smartCwpImportData.getSmartCraneMaintainPlanInfoList().size() == 0) {
                cwpLogger.logInfo("输入数据中没有桥机维修计划");
            }
            for (SmartCraneMaintainPlanInfo smartCraneMaintainPlanInfo : smartCwpImportData.getSmartCraneMaintainPlanInfoList()) {
                CWPCraneMaintainPlan cwpCraneMaintainPlan = new CWPCraneMaintainPlan();
                cwpCraneMaintainPlan = (CWPCraneMaintainPlan) BeanCopy.copyBean(smartCraneMaintainPlanInfo, cwpCraneMaintainPlan);
                cwpLogger.logInfo("桥机(No:" + cwpCraneMaintainPlan.getCraneNo() + ")需要维修");
                vesselVisit.addCWPCraneMaintainPlan(cwpCraneMaintainPlan);
            }
            //读取桥机物理移动范围
            if (smartCwpImportData.getSmartCraneMoveRangeInfoList().size() == 0) {
                cwpLogger.logInfo("输入数据中没有桥机物理移动范围信息");
            }
            for (SmartCraneMoveRangeInfo smartCraneMoveRangeInfo : smartCwpImportData.getSmartCraneMoveRangeInfoList()) {
                if (berthId.equals(smartCraneMoveRangeInfo.getBerthId())) {
//                    Validator.notNull("桥机(craneNo: " + smartCraneMoveRangeInfo.getCraneNo() + ")物理移动范围限制起始倍位", smartCraneMoveRangeInfo.getStartBayNo());
//                    Validator.notNull("桥机(craneNo: " + smartCraneMoveRangeInfo.getCraneNo() + ")物理移动范围限制终止倍位", smartCraneMoveRangeInfo.getEndBayNo());
                    if (smartCraneMoveRangeInfo.getStartBayNo() != null && smartCraneMoveRangeInfo.getEndBayNo() != null) {
                        if (!"".equals(smartCraneMoveRangeInfo.getStartBayNo()) && !"".equals(smartCraneMoveRangeInfo.getEndBayNo())) {
                            CWPCraneMoveRange cwpCraneMoveRange = new CWPCraneMoveRange();
                            cwpCraneMoveRange = (CWPCraneMoveRange) BeanCopy.copyBean(smartCraneMoveRangeInfo, cwpCraneMoveRange);
                            vesselVisit.addCWPCraneMoveRange(cwpCraneMoveRange);
                        }
                    }
                }
            }
            //读取船舶机械信息
            if (smartCwpImportData.getSmartVesselMachinesInfoList().size() == 0) {
                cwpLogger.logInfo("输入数据中没有船舶机械信息");
            }
            for (SmartVesselMachinesInfo smartVesselMachinesInfo : smartCwpImportData.getSmartVesselMachinesInfoList()) {
                if (smartVesselMachinesInfo.getVesselCode().equals(vesselVisit.getVesselCode())) {
                    Validator.notNull("船舶机械(No:" + smartVesselMachinesInfo + ")位置坐标为null", smartVesselMachinesInfo.getMachinePosition());
                    Validator.notNull("船舶机械(No:" + smartVesselMachinesInfo + ")类型为null", smartVesselMachinesInfo.getMachineType());
                    CWPMachine cwpMachine = new CWPMachine(smartVesselMachinesInfo.getMachinePosition());
                    cwpMachine = (CWPMachine) BeanCopy.copyBean(smartVesselMachinesInfo, cwpMachine);
                    vesselVisit.addCWPMachine(cwpMachine);
                }
            }
            //读取算法配置参数信息
            SmartCwpConfigurationInfo smartCwpConfigurationInfo = new SmartCwpConfigurationInfo();
            CWPConfiguration cwpConfiguration = new CWPConfiguration();
            if (smartCwpImportData.getSmartCwpConfigurationInfoList().size() == 0) {
                cwpLogger.logInfo("输入数据中没有CWP算法配置参数信息");
            }
            for (SmartCwpConfigurationInfo smartCwpConfigurationInfo1 : smartCwpImportData.getSmartCwpConfigurationInfoList()) {
                if (smartCwpConfigurationInfo1.getBerthId().equals(berthId)) {
                    smartCwpConfigurationInfo = smartCwpConfigurationInfo1;
                }
            }
            this.readDefaultConfiguration(smartCwpConfigurationInfo, cwpConfiguration);
            vesselVisit.setCwpConfiguration(cwpConfiguration);


            cwpLogger.logInfo("CWP船舶访问详细信息创建完成!");
        } catch (Exception e) {
            cwpLogger.logError("创建船舶(berthId:" + berthId + ")访问详细信息过程中发生异常！");
            e.printStackTrace();
        }
        return vesselVisit;
    }

    private void readDefaultConfiguration(SmartCwpConfigurationInfo smartCwpConfigurationInfo, CWPConfiguration cwpConfiguration) {
        //重量差，千克kg
        Integer twinWeightDiff = smartCwpConfigurationInfo.getTwinWeightDiff();
        twinWeightDiff = twinWeightDiff != null ? twinWeightDiff : CWPDefaultValue.twinWeightDiff;
        cwpConfiguration.setTwinWeightDiff(twinWeightDiff);
        //桥机安全距离，14米
        Double craneSafeSpan = smartCwpConfigurationInfo.getCraneSafeSpan();
        craneSafeSpan = craneSafeSpan != null ? craneSafeSpan : CWPDefaultValue.craneSafeSpan;
        cwpConfiguration.setCraneSafeSpan(craneSafeSpan);
        //桥机移动速度，0.75m/s
        Double craneSpeed = smartCwpConfigurationInfo.getCraneSpeed();
        craneSpeed = craneSpeed != null ? craneSpeed : CWPDefaultValue.craneSpeed;
        cwpConfiguration.setCraneSpeed(craneSpeed);
        //所有桥机平均效率，30关/小时
        Long craneMeanEfficiency = smartCwpConfigurationInfo.getCraneMeanEfficiency();
        craneMeanEfficiency = craneMeanEfficiency != null ? 3600 / craneMeanEfficiency : CWPDefaultValue.oneContainerWorkTime;
        cwpConfiguration.setCraneMeanEfficiency(craneMeanEfficiency);
        ///桥机跨机械起趴大梁移动时间，45分钟
        Long crossBarTime = smartCwpConfigurationInfo.getCrossBarTime();
        crossBarTime = crossBarTime != null ? crossBarTime * 60 : CWPDefaultValue.crossBarTime;
        cwpConfiguration.setCrossBarTime(crossBarTime);
        //船舶机械高度参数，用于判断桥机跨机械是否起趴大梁，15米
        Double machineHeight = smartCwpConfigurationInfo.getMachineHeight();
        machineHeight = machineHeight != null ? machineHeight : CWPDefaultValue.machineHeight;
        cwpConfiguration.setMachineHeight(machineHeight);
        //是否过驾驶台起大梁
        Boolean crossBridge = smartCwpConfigurationInfo.getCrossBridge();
        crossBridge = crossBridge != null ? crossBridge : CWPDefaultValue.crossBridge;
        cwpConfiguration.setCrossBridge(crossBridge);
        //是否过烟囱起大梁
        Boolean crossChimney = smartCwpConfigurationInfo.getCrossChimney();
        crossChimney = crossChimney != null ? crossChimney : CWPDefaultValue.crossChimney;
        cwpConfiguration.setCrossChimney(crossChimney);
        //分割量界限参数，15关
        Long amount = smartCwpConfigurationInfo.getAmount();
        amount = amount != null ? amount : CWPDefaultValue.amount;
        cwpConfiguration.setAmount(amount);
        //桥机退出作业的时间量参数，30分钟
        Long delCraneTimeParam = smartCwpConfigurationInfo.getDelCraneTimeParam();
        delCraneTimeParam = delCraneTimeParam != null ? delCraneTimeParam * 60 : CWPDefaultValue.delCraneTimeParam;
        cwpConfiguration.setDelCraneTimeParam(delCraneTimeParam);
        //桥机加入作业的时间量参数，30分钟
        Long addCraneTimeParam = smartCwpConfigurationInfo.getAddCraneTimeParam();
        addCraneTimeParam = addCraneTimeParam != null ? addCraneTimeParam * 60 : CWPDefaultValue.addCraneTimeParam;
        cwpConfiguration.setAddCraneTimeParam(addCraneTimeParam);
        //建议开路数
        Integer craneAdviceNumber = smartCwpConfigurationInfo.getCraneAdviceNumber();
        cwpConfiguration.setCraneAdviceNumber(craneAdviceNumber);
        //重点倍作业时间量增加参数，360分钟
        Long keyBayWorkTime = smartCwpConfigurationInfo.getKeyBayWorkTime();
        keyBayWorkTime = keyBayWorkTime != null ? keyBayWorkTime * 60 : CWPDefaultValue.keyBayWorkTime;
        cwpConfiguration.setKeyBayWorkTime(keyBayWorkTime);
        //分割倍作业时间量增加参数，60分钟
        Long dividedBayWorkTime = smartCwpConfigurationInfo.getDividedBayWorkTime();
        dividedBayWorkTime = dividedBayWorkTime != null ? dividedBayWorkTime * 60 : CWPDefaultValue.dividedBayWorkTime;
        cwpConfiguration.setDividedBayWorkTime(dividedBayWorkTime);
        //保持在上次作业的倍位作业时间量增加参数，600分钟
        Long keepBayWorkTime = smartCwpConfigurationInfo.getKeepSelectedBayWorkTime();
        keepBayWorkTime = keepBayWorkTime != null ? keepBayWorkTime * 60 : CWPDefaultValue.keepSelectedBayWorkTime;
        cwpConfiguration.setKeepSelectedBayWorkTime(keepBayWorkTime);
        //故障箱处理时间，30分钟
        Long breakDownCntTime = smartCwpConfigurationInfo.getBreakDownCntTime();
        breakDownCntTime = breakDownCntTime != null ? breakDownCntTime * 60 : CWPDefaultValue.breakDownCntTime;
        cwpConfiguration.setBreakDownCntTime(breakDownCntTime);
        //装卸策略，即边装边卸：BLD、一般装卸：LD
        String ldStrategy = smartCwpConfigurationInfo.getLdStrategy();
        ldStrategy = ldStrategy != null ? ldStrategy.equals("LD") ? CWPDomain.LD_STRATEGY_LD : CWPDomain.LD_STRATEGY_BLD : CWPDomain.LD_STRATEGY_BLD;
        cwpConfiguration.setLdStrategy(ldStrategy);
        //自动减桥机参数，当剩余多少量时可以减掉旁边的桥机
        Long autoDelCraneAmount = smartCwpConfigurationInfo.getAutoDelCraneAmount();
        autoDelCraneAmount = autoDelCraneAmount != null ? autoDelCraneAmount : CWPDefaultValue.autoDelCraneAmount;
        cwpConfiguration.setAutoDelCraneAmount(autoDelCraneAmount);
        Boolean keyBay = smartCwpConfigurationInfo.getKeyBay();
        keyBay = keyBay != null ? keyBay : CWPDefaultValue.keyBay;
        cwpConfiguration.setKeyBay(keyBay);
        Boolean divideBay = smartCwpConfigurationInfo.getDivideBay();
        divideBay = divideBay != null ? divideBay : CWPDefaultValue.dividedBay;
        cwpConfiguration.setDivideBay(divideBay);
        Boolean divideByMaxRoad = smartCwpConfigurationInfo.getDivideByMaxRoad();
        divideByMaxRoad = divideByMaxRoad != null ? divideByMaxRoad : CWPDefaultValue.divideByMaxRoad;
        cwpConfiguration.setDivideByMaxRoad(divideByMaxRoad);
        //优先作业装船倍位参数
        Integer loadFirstParam = smartCwpConfigurationInfo.getLoadFirstParam();
        loadFirstParam = loadFirstParam != null ? loadFirstParam : CWPDefaultValue.loadFirstParam;
        cwpConfiguration.setLoadFirstParam(loadFirstParam);
    }

    public MOVessel buildMOVessel(VesselVisit vesselVisit, SmartCwpImportData smartCwpImportData) {
        Validator.notNull("船舶访问信息VesselVisit为null", vesselVisit);
        String vesselCode = vesselVisit.getVesselCode();
        cwpLogger.logInfo("开始创建船舶结构信息, vesselCode: " + vesselCode);
        MOVessel moVessel = null;
        try {
            moVessel = new MOVessel(vesselCode);
            //建立舱信息
            Validator.listNotEmpty("缺少舱信息", smartCwpImportData.getSmartVpsVslHatchsInfoList());
            for (SmartVpsVslHatchsInfo smartVpsVslHatchsInfo : smartCwpImportData.getSmartVpsVslHatchsInfoList()) {
                if (smartVpsVslHatchsInfo.getVesselCode().equals(vesselCode)) {//找到舱信息
                    Long hatchId = smartVpsVslHatchsInfo.getHatchId();
                    MOHatch moHatch = new MOHatch(hatchId);
                    //设置各种属性
                    Validator.notNull("舱(Id:" + hatchId + ")位置坐标为null", smartVpsVslHatchsInfo.getHatchPosition());
                    moHatch.setHatchPosition(smartVpsVslHatchsInfo.getHatchPosition());
                    Validator.notNull("舱(Id:" + hatchId + ")长度为null", smartVpsVslHatchsInfo.getHatchLength());
                    moHatch.setHatchLength(smartVpsVslHatchsInfo.getHatchLength());
                    moVessel.addMOHatch(moHatch);
                }
            }
            //各舱创建贝位信息
            Validator.listNotEmpty("缺少倍位信息", smartCwpImportData.getSmartVpsVslBaysInfoList());
            for (SmartVpsVslBaysInfo smartVpsVslBaysInfo : smartCwpImportData.getSmartVpsVslBaysInfoList()) {
                if (smartVpsVslBaysInfo.getVesselCode().equals(vesselCode)) {//找到贝位信息
                    //逐舱遍历
                    Long bayId = smartVpsVslBaysInfo.getBayId();
                    Long hatchId = smartVpsVslBaysInfo.getHatchId();
                    Integer bayNo = Integer.valueOf(smartVpsVslBaysInfo.getBayNo());
                    String aboveOrBelow = smartVpsVslBaysInfo.getDeckOrHatch();
                    Validator.notNull("倍位信息中甲板上、下字段为null", aboveOrBelow);
                    aboveOrBelow = aboveOrBelow.equals("D") ? CWPDomain.BOARD_ABOVE : CWPDomain.BOARD_BELOW;
                    String bayKey = bayNo + "" + aboveOrBelow;
                    MOBay moBay = new MOBay(bayId, hatchId, bayNo, aboveOrBelow, bayKey);
                    moVessel.addMOBayById(moBay);
                    moVessel.addMOBayByKey(moBay);
                    //初始化舱含有几个bayId、bayNo和bayKey信息
                    MOHatch moHatch = moVessel.getMOHatchByHatchId(hatchId);
                    Validator.notNull("倍位信息中的hatchId(" + hatchId + ")与舱信息中的hatchId不匹配!", moHatch);
                    moHatch.addMOBayId(bayId);
                    moHatch.addMOBayNo(bayNo);
                    moHatch.addMOBayKey(bayKey);
                }
            }
            //各贝位创建排信息
            Validator.listNotEmpty("缺少排信息", smartCwpImportData.getSmartVpsVslRowsInfoList());
            for (SmartVpsVslRowsInfo smartVpsVslRowsInfo : smartCwpImportData.getSmartVpsVslRowsInfoList()) {
                if (smartVpsVslRowsInfo.getVesselCode().equals(vesselCode)) {//找到排位信息
                    Long bayId = smartVpsVslRowsInfo.getBayId();
                    Integer rowNo = Integer.valueOf(smartVpsVslRowsInfo.getRowNo());
                    MORow moRow = new MORow(bayId, rowNo);
                    //初始化该倍有多少个排
                    MOBay moBay = moVessel.getMOBayByBayId(bayId);
                    moBay.addMORow(moRow);
                }
            }
            //解析船箱位数据，创建Slot信息
            Validator.listNotEmpty("缺少船箱位信息", smartCwpImportData.getSmartVpsVslLocationsInfoList());
            for (SmartVpsVslLocationsInfo smartVpsVslLocationsInfo : smartCwpImportData.getSmartVpsVslLocationsInfoList()) {
                if (smartVpsVslLocationsInfo.getVesselCode().equals(vesselCode)) {//找到位置信息
                    String vLocation = smartVpsVslLocationsInfo.getLocation();
                    Long bayId = smartVpsVslLocationsInfo.getBayId();
                    Integer rowNo = null;
                    try {
                        MOSlotPosition moSlotPosition = new MOSlotPosition(vLocation);
                        MOBay moBay = moVessel.getMOBayByBayId(bayId);
                        String aboveOrBelow = moBay.getAboveOrBelow();
                        moSlotPosition.setAboveOrBelow(aboveOrBelow);
                        String size = smartVpsVslLocationsInfo.getSize();
                        MOSlot moSlot = new MOSlot(bayId, moSlotPosition, aboveOrBelow, size);
                        //将MOSlot信息保存在MOVessel里
                        moVessel.addMOSlot(moSlot);
                        //要根据船箱位信息，初始化该倍位下每排的最大层号和最小层号
                        MORow moRow = moBay.getMORowByRowNo(moSlotPosition.getRowNo());
                        if (moRow == null) {
                            rowNo = moSlotPosition.getRowNo();
                        }
                        moRow.addMOSlot(moSlot);
                    } catch (Exception e) {
                        e.printStackTrace();
                        cwpLogger.logError("输入数据不正确: 解析船箱位(" + vLocation + ")错误，找不到该船箱位" + rowNo + "排信息。");
                    }
                }
            }
            //读取舱盖板信息
            if (smartCwpImportData.getSmartVpsVslHatchcoversInfoList().size() == 0) {
                cwpLogger.logInfo("输入数据中没有舱盖板信息");
            }
            for (SmartVpsVslHatchcoversInfo smartVpsVslHatchcoversInfo : smartCwpImportData.getSmartVpsVslHatchcoversInfoList()) {
                if (vesselCode.equals(smartVpsVslHatchcoversInfo.getVesselCode())) {
                    if (smartVpsVslHatchcoversInfo.getHatchFromRowNo() != null && smartVpsVslHatchcoversInfo.getHatchToRowNo() != null && smartVpsVslHatchcoversInfo.getDeckFromRowNo() != null && smartVpsVslHatchcoversInfo.getDeckToRowNo() != null) {
                        MOHatchCover moHatchCover = new MOHatchCover();
                        moHatchCover = (MOHatchCover) BeanCopy.copyBean(smartVpsVslHatchcoversInfo, moHatchCover);
                        moHatchCover.setDeckFromRowNo(Integer.valueOf(smartVpsVslHatchcoversInfo.getDeckFromRowNo()));
                        moHatchCover.setDeckToRowNo(Integer.valueOf(smartVpsVslHatchcoversInfo.getDeckToRowNo()));
                        moHatchCover.setHatchFromRowNo(Integer.valueOf(smartVpsVslHatchcoversInfo.getHatchFromRowNo()));
                        moHatchCover.setHatchToRowNo(Integer.valueOf(smartVpsVslHatchcoversInfo.getHatchToRowNo()));
                        moVessel.addMOHatchCover(moHatchCover);
                    }
                }
            }
        } catch (Exception e) {
            cwpLogger.logError("创建船舶(vesselCode:" + vesselCode + ")结构信息过程中发生异常!");
            e.printStackTrace();
        }
        cwpLogger.logInfo("CWP船舶结构信息创建完成!");
        return moVessel;
    }

    public void processWorkFlow(VesselVisit vesselVisit) {
        try {
            MOProcess moProcess = new MOProcess(vesselVisit);
            moProcess.processWorkFlow();
        } catch (Exception e) {
            cwpLogger.logError("生成作业工艺过程中发生异常!");
            e.printStackTrace();
        }
    }

    public void processOrder(VesselVisit vesselVisit) {
        try {
            MOProcess moProcess = new MOProcess(vesselVisit);
            moProcess.processOrder();
        } catch (Exception e) {
            cwpLogger.logError("生成作业顺序过程中发生异常!");
            e.printStackTrace();
        }
    }

    public void processCWP(VesselVisit vesselVisit) {
        try {
            CWPProcess cwpProcess = new CWPProcess(vesselVisit);
            cwpProcess.processCWP();
        } catch (Exception e) {
            cwpLogger.logError("CWP算法计算过程中发生异常!");
            e.printStackTrace();
        }

    }

    public void adjustCwpResult(VesselVisit vesselVisit) {
        try {
            ResultManager resultManager = new ResultManager(vesselVisit);
            resultManager.adjustCwpResult();
        } catch (Exception e) {
            cwpLogger.logError("调整CWP结果过程中发生异常!");
            e.printStackTrace();
        }
    }

    public void generateWorkFlowAndOrderResult(VesselVisit vesselVisit) {
        try {
            ResultManager resultManager = new ResultManager(vesselVisit);
            resultManager.generateWorkFlowAndOrder();
        } catch (Exception e) {
            cwpLogger.logError("返回作业工艺和顺序结果对象过程中发生异常!");
            e.printStackTrace();
        }
    }
}
