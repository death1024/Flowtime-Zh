package cn.entertech.affectiveclouddemo.ui.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.text.Html
import android.util.Log
import cn.entertech.affectiveclouddemo.R
import cn.entertech.affectiveclouddemo.app.Constant
import cn.entertech.affectiveclouddemo.database.MeditationDao
import cn.entertech.affectiveclouddemo.database.UserLessonRecordDao
import cn.entertech.affectiveclouddemo.model.MeditationEntity
import cn.entertech.affectiveclouddemo.model.UserLessonEntity
import cn.entertech.affectiveclouddemo.model.meditation.*
import cn.entertech.affectiveclouddemo.ui.fragment.MeditationBaseFragment
import cn.entertech.affectiveclouddemo.ui.fragment.MeditationFragment
import cn.entertech.affectiveclouddemo.ui.fragment.MeditationLargeFragment
import cn.entertech.affectiveclouddemo.utils.DataSender
import cn.entertech.affectiveclouddemo.utils.MeditationStatusPlayer
import cn.entertech.affectiveclouddemo.utils.getCurrentTimeFormat
import cn.entertech.affectiveclouddemo.utils.reportfileutils.FragmentBuffer
import cn.entertech.affectivecloudsdk.*
import cn.entertech.affectivecloudsdk.entity.Error
import cn.entertech.affectivecloudsdk.entity.Service
import cn.entertech.affectivecloudsdk.interfaces.Callback
import cn.entertech.affectivecloudsdk.interfaces.Callback2
import cn.entertech.ble.multiple.MultipleBiomoduleBleManager
import cn.entertech.bleuisdk.ui.DeviceUIConfig
import cn.entertech.flowtime.utils.reportfileutils.MeditaionInterruptManager
import com.google.gson.GsonBuilder
import com.orhanobut.logger.Logger
import java.util.ArrayList
import java.util.HashMap

class MeditationActivity : BaseActivity() {

    private var fragment: MeditationBaseFragment? = null

    var handler: Handler = Handler()
    private lateinit var rawListener: (ByteArray) -> Unit
    private lateinit var heartRateListener: (Int) -> Unit
    var enterAffectiveCloudManager: EnterAffectiveCloudManager? = null
    var bleManager: MultipleBiomoduleBleManager = DeviceUIConfig.getInstance(this).managers[0]
    var meditationStartTime: Long? = null
    var websocketAddress = "wss://server.affectivecloud.cn/ws/algorithm/v2/"

    var meditationEndTime: String? = null
    val APP_KEY: String = "4dea1014-c56b-11e9-86cb-0242ac120003"
    val APP_SECRET: String = "214af6b41cd6e6588b4a30da7ac48595"

//    val APP_KEY: String = "015b7118-b81e-11e9-9ea1-8c8590cb54f9"
//    val APP_SECRET: String = "cd9c757ae9a7b7e1cff01ee1bb4d4f98"
    var fragmentBuffer = FragmentBuffer()
    var meditationStatusPlayer: MeditationStatusPlayer? = null

    lateinit var reportMeditationData: ReportMeditationDataEntity
    private var userLessonEntity: UserLessonEntity? = null
    private var meditaiton: MeditationEntity? = null

    var userLessonStartTime: String? = null
    private var meditationId: Long = -1
    private var mRecordId: Long = -1
    var isFirstReceiveData = true
    //private lateinit var dataSender: DataSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meditation)
        initFullScreenDisplay()
        setStatusBarLight()
        meditationStatusPlayer = MeditationStatusPlayer(this)
        userLessonStartTime = getCurrentTimeFormat()
        initFragment()
        initAffectiveCloudManager()
        initFlowtimeManager()
        //dataSender = DataSender("192.168.247.76", 5000)
    }

    fun initFragment() {
        if (resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels) {
            fragment = MeditationLargeFragment()
        } else {
            fragment = MeditationFragment()
        }
        supportFragmentManager.beginTransaction().replace(R.id.fl_container, fragment!!)
            .commitAllowingStateLoss()
    }

    override fun onBackPressed() {
        showDialog()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        initFragment()
    }


    var onDeviceConnectListener = fun(_: String) {
        if (enterAffectiveCloudManager!!.isWebSocketOpen()) {
            meditationStatusPlayer?.playConnectAudio()
        }
        MeditaionInterruptManager.getInstance()
            .popInterrupt(MeditaionInterruptManager.INTERRUPT_TYPE_DEVICE)
        runOnUiThread {
            fragment?.handleDeviceConnect()
            if (enterAffectiveCloudManager!!.isInited()) {
                enterAffectiveCloudManager!!.restore(object : Callback {
                    override fun onError(error: cn.entertech.affectivecloudsdk.entity.Error?) {

                    }

                    override fun onSuccess() {
                        bleManager.startHeartAndBrainCollection()
                        bleManager.startContact()
                    }
                })
            } else {
                enterAffectiveCloudManager!!.init(object :
                    Callback {
                    override fun onError(error: cn.entertech.affectivecloudsdk.entity.Error?) {
                    }

                    override fun onSuccess() {
                        bleManager.startHeartAndBrainCollection()
                        bleManager.startContact()
                    }

                })
            }

        }
    }

    var onDeviceDisconnectListener = fun(_: String) {
        if (enterAffectiveCloudManager!!.isWebSocketOpen()) {
            meditationStatusPlayer?.playDisconnectAudio()
        }
        MeditaionInterruptManager.getInstance()
            .pushInterrupt(MeditaionInterruptManager.INTERRUPT_TYPE_DEVICE)
        runOnUiThread {
            fragment?.handleInterruptTip()
        }
    }

    fun initFlowtimeManager() {
        rawListener = fun(bytes: ByteArray) {
            if (enterAffectiveCloudManager!!.isInit) {
                enterAffectiveCloudManager?.appendEEGData(bytes)
            }
        }
        heartRateListener = fun(heartRate: Int) {
            if (enterAffectiveCloudManager!!.isInit) {
                enterAffectiveCloudManager?.appendHeartRateData(heartRate)
            }
        }
        bleManager.addRawDataListener(rawListener)
        bleManager.addHeartRateListener(heartRateListener)

        bleManager.addConnectListener(onDeviceConnectListener)
        bleManager.addDisConnectListener(onDeviceDisconnectListener)
    }

    var isFirstIn = true
    var webSocketConnectListener = fun() {
        runOnUiThread {
            if (bleManager.isConnected() && !isFirstIn) {
                meditationStatusPlayer?.playConnectAudio()
            }
            isFirstIn = false
            MeditaionInterruptManager.getInstance()
                .popInterrupt(MeditaionInterruptManager.INTERRUPT_TYPE_NET)
            fragment?.handleWebSocketConnect()
        }
    }
    var websocketDisconnectListener = fun(_:String) {
        runOnUiThread {
            if (bleManager.isConnected()) {
                meditationStatusPlayer?.playDisconnectAudio()
            }

            MeditaionInterruptManager.getInstance()
                .pushInterrupt(MeditaionInterruptManager.INTERRUPT_TYPE_NET)
            bleManager.stopHeartAndBrainCollection()
            fragment?.handleWebSocketDisconnect()
        }
    }

    fun initAffectiveCloudManager() {

        var availableAffectiveServices =
            listOf(
                Service.ATTENTION,
                Service.PRESSURE,
                Service.RELAXATION,
                Service.AROUSAL,
                Service.PLEASURE,
                Service.COHERENCE
            )
        var availableBioServices = listOf(Service.EEG, Service.HR)
        var biodataSubscribeParams = BiodataSubscribeParams.Builder()
            .requestHR()
            .requestEEG()
            .build()

        var algorithmParamsEEG =
            AlgorithmParamsEEG.Builder()
                .filterMode(AlgorithmParams.FilterMode.HARD)
                .powerMode(AlgorithmParams.PowerMode.DB)
                .tolerance(AlgorithmParams.Tolerance.LEVEL_2)
                .build()
        var algorithmParams = AlgorithmParams.Builder().eeg(algorithmParamsEEG).build()
        var affectiveSubscribeParams = AffectiveSubscribeParams.Builder()
            .requestAttention()
            .requestRelaxation()
            .requestPressure()
            .requestArousal()
            .requestPleasure()
            .requestCoherence()
            .build()
        var enterAffectiveCloudConfig =
            EnterAffectiveCloudConfig.Builder(APP_KEY, APP_SECRET, "0")
                .url(websocketAddress)
                .timeout(10000)
                .availableBiodataServices(availableBioServices)
                .availableAffectiveServices(availableAffectiveServices)
                .biodataSubscribeParams(biodataSubscribeParams)
                .affectiveSubscribeParams(affectiveSubscribeParams)
                .uploadCycle(1)
                .algorithmParams(algorithmParams)
                .build()
        enterAffectiveCloudManager = EnterAffectiveCloudManager(enterAffectiveCloudConfig)

        enterAffectiveCloudManager!!.addBiodataRealtimeListener {
            runOnUiThread {
                //            Logger.d("bio realtime data is " + it.toString())
                if (it?.realtimeEEGData != null) {
                    if (isFirstReceiveData) {
                        meditationStartTime = System.currentTimeMillis()
                        fragmentBuffer.fileName = getCurrentTimeFormat(meditationStartTime!!)
                        isFirstReceiveData = false
                    }
                }

                fragment?.showHeart(it?.realtimeHrData?.hr?.toInt())
                fragment?.showBreathCoherence(it?.realtimeHrData?.hr)
                fragment?.showBrain(it?.realtimeEEGData)
                fragment?.dealQuality(it?.realtimeEEGData?.quality)
                //val jsonBuilder = GsonBuilder().create()
                //val hrDataJson = it?.realtimeHrData?.let { hrData -> jsonBuilder.toJson(hrData) }
                //val eegDataJson = it?.realtimeEEGData?.let { eegData -> jsonBuilder.toJson(eegData) }

                //hrDataJson?.let { json -> dataSender.send("HRData: $json") }
                //eegDataJson?.let { json -> dataSender.send("EEGData: $json") }
            }
        }
        enterAffectiveCloudManager!!.addAffectiveDataRealtimeListener {
//                        Logger.d("affective realtime data is " + it.toString())
            runOnUiThread {
                val jsonBuilder = GsonBuilder().create()

                fragment?.showAttention(it?.realtimeAttentionData?.attention?.toFloat())
                fragment?.showRelaxation(it?.realtimeRelaxationData?.relaxation?.toFloat())
                fragment?.showPressure(it?.realtimePressureData?.pressure?.toFloat())
                fragment?.showArousal(it?.realtimeArousalData?.arousal?.toFloat())
                fragment?.showPleasure(it?.realtimePleasureData?.pleasure?.toFloat())
                fragment?.showCoherence(it?.realtimeCoherenceData?.coherence?.toFloat())
            }
        }
        if (bleManager.isConnected()) {
            enterAffectiveCloudManager?.init(object : Callback {
                override fun onError(error: cn.entertech.affectivecloudsdk.entity.Error?) {
//                    Logger.d("affectivecloudmanager init failed:" + error.toString())
                }

                override fun onSuccess() {
//                    Logger.d("affectivecloudmanager init success:")
                    bleManager.startHeartAndBrainCollection()
                }
            })
        }

        enterAffectiveCloudManager?.addWebSocketConnectListener(
            webSocketConnectListener
        )
        enterAffectiveCloudManager?.addWebSocketDisconnectListener(
            websocketDisconnectListener
        )
    }


    @SuppressLint("NewApi")
    fun showDialog() {
        var dialog = AlertDialog.Builder(this)
            .setTitle(Html.fromHtml("<font color='${R.color.colorDialogTitle}'>结束体验</font>",Html.FROM_HTML_MODE_LEGACY))
            .setMessage(Html.fromHtml("<font color='${R.color.colorDialogContent}'>结束体验并获取分析报告</font>",Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(
                Html.fromHtml("<font color='${R.color.colorDialogExit}'>确定</font>",Html.FROM_HTML_MODE_LEGACY)
            ) { dialog, _ ->
                dialog.dismiss()
                finishMeditation()
            }
            .setNegativeButton(
                Html.fromHtml("<font color='${R.color.colorDialogCancel}'>取消</font>",Html.FROM_HTML_MODE_LEGACY)
            ) { dialog, _ ->
                dialog.dismiss()
            }.create()
        dialog.show()
    }

    var finishRunnable = {
        exitWithoutMeditation()
    }

    fun finishMeditation() {
//        if (mediaPlayer == null || mediaPlayer!!.currentPosition * 1f / mediaPlayer!!.duration < 0.75) {
//            ToastUtil.toastShort(this, "Meditation didn't finish, no report generate.")
//            finish()
//        } pic_arousal_pleasure_emotion_else {
        startFinishTimer()
        reportMeditationData = ReportMeditationDataEntity()
        meditationEndTime = getCurrentTimeFormat()
        bleManager.stopHeartAndBrainCollection()
        if (meditationTimeError() || !enterAffectiveCloudManager!!.isWebSocketOpen()) {
            exitWithoutMeditation()
        } else {
            getReportAndExit()
        }
    }

    fun startFinishTimer() {
        handler.postDelayed({
            finishRunnable
        }, 10000)
    }

    fun meditationTimeError(): Boolean {
        if (meditationStartTime == null) {
            return true
        } else {
            var meditationEndTime = System.currentTimeMillis()
            var duration = (meditationEndTime - meditationStartTime!!) / 1000 / 60
            if (duration < 1) {
                return true
            }
        }
        return false
    }


    fun saveReportFile(reportMeditationData: ReportMeditationDataEntity) {
        fragmentBuffer.appendMeditationReport(
            reportMeditationData,
            meditationStartTime!!,
            MeditaionInterruptManager.getInstance().interruptTimestampList
        )
    }

    fun saveMeditationInDB(report: ReportMeditationDataEntity) {
        var meditationDao = MeditationDao(this)
        meditaiton = MeditationEntity()
        meditationId = -System.currentTimeMillis()
        meditaiton!!.id = meditationId
        meditaiton!!.startTime = fragmentBuffer.fileName
        meditaiton!!.finishTime = meditationEndTime
        meditaiton!!.attentionAvg = report.reportAttentionEntity?.attentionAvg!!.toFloat()
        meditaiton!!.attentionMax =
            java.util.Collections.max(report.reportAttentionEntity?.attentionRec).toFloat()
        meditaiton!!.attentionMin =
            java.util.Collections.min(report.reportAttentionEntity?.attentionRec).toFloat()
        meditaiton!!.heartRateAvg = report.reportHRDataEntity?.hrAvg!!.toFloat()
        meditaiton!!.heartRateMax = report.reportHRDataEntity?.hrMax!!.toFloat()
        meditaiton!!.heartRateMin = report.reportHRDataEntity?.hrMin!!.toFloat()
        meditaiton!!.heartRateVariabilityAvg = report.reportHRDataEntity?.hrvAvg!!.toFloat()
        meditaiton!!.relaxationAvg = report.reportRelaxationEntity?.relaxationAvg!!.toFloat()
        meditaiton!!.relaxationMax =
            java.util.Collections.max(report.reportRelaxationEntity?.relaxationRec).toFloat()
        meditaiton!!.relaxationMin =
            java.util.Collections.min(report.reportRelaxationEntity?.relaxationRec).toFloat()
        meditaiton!!.user = 0
//        var reportFileUri =
//            "${SettingManager.getInstance().userId}/${courseId}/${lessonId}/${fragmentBuffer.fileName}"
        meditaiton!!.meditationFile = fragmentBuffer.fileName
        meditationDao.create(meditaiton)
    }

    fun saveUserLessonInDB() {
        var userLessonRecordDao = UserLessonRecordDao(this)
        userLessonEntity = UserLessonEntity()
        mRecordId = -System.currentTimeMillis()
        userLessonEntity!!.id = mRecordId
        userLessonEntity!!.lessonName = ""
        userLessonEntity!!.courseName = ""
        userLessonEntity!!.lessonId = 0
        userLessonEntity!!.courseId = 0
        userLessonEntity!!.startTime = userLessonStartTime
        userLessonEntity!!.finishTime = meditationEndTime
        userLessonEntity!!.user = 0
        userLessonEntity!!.meditation = meditationId
        userLessonRecordDao.create(userLessonEntity)
    }

    fun toDataActivity() {
        var intent = Intent(this, DataActivity::class.java)
        intent.putExtra(Constant.RECORD_ID, mRecordId)
        startActivity(intent)
        finish()
    }

    fun exitWithMeditation(reportMeditationData: ReportMeditationDataEntity) {
        if (reportMeditationData.isDataSetCompletely()) {
            Log.d("#######","report data completly")
            handler.removeCallbacks(finishRunnable)
            saveReportFile(reportMeditationData)
            saveMeditationInDB(reportMeditationData)
            saveUserLessonInDB()
            enterAffectiveCloudManager?.release(object : Callback {
                override fun onError(error: Error?) {
                    Log.d("#######","report release onError")

                }

                override fun onSuccess() {

                    Log.d("#######","report release onSuccess")
                    toDataActivity()
                }

            })
        }
    }

    fun exitWithoutMeditation() {
//        saveUserLessonInDB()
//        Toast.makeText(activity!!,"体验时间过短！",Toast.LENGTH_SHORT).show()
        if (enterAffectiveCloudManager!!.isWebSocketOpen()) {
            enterAffectiveCloudManager?.release(object : Callback {
                override fun onError(error: Error?) {

                }

                override fun onSuccess() {
                    finish()
                }

            })
        } else {
            finish()
        }
    }

    fun getReportAndExit() {
        enterAffectiveCloudManager?.getBiodataReport(object : Callback2<HashMap<Any, Any?>> {
            override fun onError(error: Error?) {
            }

            override fun onSuccess(t: HashMap<Any, Any?>?) {
                Logger.d("report bio is " + t.toString())
                if (t == null) {
                    return
                }
                var reportHRDataEntity = ReportHRDataEntity()
                @Suppress("UNCHECKED_CAST")
                var hrMap = t["hr-v2"] as Map<Any, Any?>
                if (hrMap.containsKey("hr_avg")) {
                    reportHRDataEntity.hrAvg = hrMap["hr_avg"] as Double
                }
                if (hrMap.containsKey("hr_max")) {
                    reportHRDataEntity.hrMax = hrMap["hr_max"] as Double
                }
                if (hrMap.containsKey("hr_min")) {
                    reportHRDataEntity.hrMin = hrMap["hr_min"] as Double
                }
                if (hrMap.containsKey("hr_rec")) {
                    @Suppress("UNCHECKED_CAST")
                    reportHRDataEntity.hrRec = hrMap["hr_rec"] as ArrayList<Double>
                }
                if (hrMap.containsKey("hrv_rec")) {
                    @Suppress("UNCHECKED_CAST")
                    reportHRDataEntity.hrvRec = hrMap["hrv_rec"] as ArrayList<Double>
                }
                if (hrMap.containsKey("hrv_avg")) {
                    reportHRDataEntity.hrvAvg = hrMap["hrv_avg"] as Double
                }
                reportMeditationData.reportHRDataEntity = reportHRDataEntity
                var reportEEGDataEntity = ReportEEGDataEntity()
                @Suppress("UNCHECKED_CAST")
                var eegMap = t["eeg"] as Map<Any, Any?>
                if (eegMap.containsKey("eeg_alpha_curve")) {
                    @Suppress("UNCHECKED_CAST")
                    reportEEGDataEntity.alphaCurve = eegMap["eeg_alpha_curve"] as ArrayList<Double>
                }
                if (eegMap.containsKey("eeg_beta_curve")) {
                    @Suppress("UNCHECKED_CAST")
                    reportEEGDataEntity.betaCurve = eegMap["eeg_beta_curve"] as ArrayList<Double>
                }
                if (eegMap.containsKey("eeg_theta_curve")) {
                    @Suppress("UNCHECKED_CAST")
                    reportEEGDataEntity.thetaCurve = eegMap["eeg_theta_curve"] as ArrayList<Double>
                }
                if (eegMap.containsKey("eeg_delta_curve")) {
                    @Suppress("UNCHECKED_CAST")
                    reportEEGDataEntity.deltaCurve = eegMap["eeg_delta_curve"] as ArrayList<Double>
                }
                if (eegMap.containsKey("eeg_gamma_curve")) {
                    @Suppress("UNCHECKED_CAST")
                    reportEEGDataEntity.gammaCurve = eegMap["eeg_gamma_curve"] as ArrayList<Double>
                }
                reportMeditationData.reportEEGDataEntity = reportEEGDataEntity

                enterAffectiveCloudManager?.getAffectiveDataReport(object :
                    Callback2<HashMap<Any, Any?>> {
                    override fun onError(error: Error?) {
                        Logger.d("report affectve is error: " + error)
                    }

                    override fun onSuccess(t: HashMap<Any, Any?>?) {
                        Logger.d("report affectve is " + t.toString())
                        if (t == null) {
                            return
                        }
                        var reportAttentionEntity = ReportAttentionEntity()
                        @Suppress("UNCHECKED_CAST")
                        var attentionMap = t["attention"] as Map<Any, Any?>
                        if (attentionMap.containsKey("attention_avg")) {
                            Logger.d("attention avg is " + attentionMap["attention_avg"] as Double)
                            reportAttentionEntity.attentionAvg =
                                attentionMap["attention_avg"] as Double
                        }
                        if (attentionMap.containsKey("attention_rec")) {
                            @Suppress("UNCHECKED_CAST")
                            reportAttentionEntity.attentionRec =
                                attentionMap["attention_rec"] as ArrayList<Double>
                        }
                        reportMeditationData.reportAttentionEntity = reportAttentionEntity
                        var reportRelaxationEntity = ReportRelaxationEntity()
                        @Suppress("UNCHECKED_CAST")
                        var relaxationMap = t["relaxation"] as Map<Any, Any?>
                        if (relaxationMap.containsKey("relaxation_avg")) {
                            reportRelaxationEntity.relaxationAvg =
                                relaxationMap["relaxation_avg"] as Double
                        }
                        if (relaxationMap.containsKey("relaxation_rec")) {
                            @Suppress("UNCHECKED_CAST")
                            reportRelaxationEntity.relaxationRec =
                                relaxationMap["relaxation_rec"] as ArrayList<Double>
                        }
                        reportMeditationData.reportRelaxationEntity = reportRelaxationEntity

                        var reportPressureEntity = ReportPressureEntity()

                        @Suppress("UNCHECKED_CAST")
                        var pressureMap = t["pressure"] as Map<Any, Any?>
                        if (pressureMap.containsKey("pressure_avg")) {
                            reportPressureEntity.pressureAvg = pressureMap["pressure_avg"] as Double
                        }
                        if (pressureMap.containsKey("pressure_rec")) {
                            @Suppress("UNCHECKED_CAST")
                            reportPressureEntity.pressureRec =
                                pressureMap["pressure_rec"] as ArrayList<Double>
                        }
                        reportMeditationData.reportPressureEntity = reportPressureEntity

                        var reportPleasureEntity = ReportPleasureEntity()
                        @Suppress("UNCHECKED_CAST")
                        var pleasureMap = t["pleasure"] as Map<Any, Any?>
                        if (pleasureMap.containsKey("pleasure_avg")) {
                            reportPleasureEntity.pleasureAvg = pleasureMap["pleasure_avg"] as Double
                        }
                        if (pleasureMap.containsKey("pleasure_rec")) {
                            @Suppress("UNCHECKED_CAST")
                            reportPleasureEntity.pleasureRec =
                                pleasureMap["pleasure_rec"] as ArrayList<Double>
                        }
                        reportMeditationData.reportPleasureEntity = reportPleasureEntity

                        var reportCoherenceEntity = ReportCoherenceEntity()
                        @Suppress("UNCHECKED_CAST")
                        var coherenceMap = t["coherence"] as Map<Any, Any?>
                        if (coherenceMap.containsKey("coherence_avg")) {
                            reportCoherenceEntity.coherenceAvg = coherenceMap["coherence_avg"] as Double
                        }
                        if (coherenceMap.containsKey("coherence_duration")) {
                            reportCoherenceEntity.coherenceDuration = coherenceMap["coherence_duration"] as Double
                        }
                        if (coherenceMap.containsKey("coherence_rec")) {
                            @Suppress("UNCHECKED_CAST")
                            reportCoherenceEntity.coherenceRec =
                                coherenceMap["coherence_rec"] as ArrayList<Double>
                        }
                        if (coherenceMap.containsKey("coherence_flag")) {
                            @Suppress("UNCHECKED_CAST")
                            reportCoherenceEntity.coherenceFlag =
                                coherenceMap["coherence_flag"] as ArrayList<Double>
                        }
                        reportMeditationData.reportCoherenceEntity = reportCoherenceEntity
                        exitWithMeditation(reportMeditationData)
                    }

                })
            }

        })
    }

    fun restore() {
        if (enterAffectiveCloudManager!!.isInited()) {
            enterAffectiveCloudManager?.restore(object : Callback {
                override fun onError(error: Error?) {

                }

                override fun onSuccess() {
                    bleManager.startHeartAndBrainCollection()
                }

            })
        } else {
            enterAffectiveCloudManager?.init(object : Callback {
                override fun onError(error: Error?) {

                }

                override fun onSuccess() {
                    bleManager.startHeartAndBrainCollection()
                }

            })
        }
    }


    override fun onDestroy() {
        bleManager.removeConnectListener(onDeviceConnectListener)
        bleManager.removeDisConnectListener(onDeviceDisconnectListener)
        enterAffectiveCloudManager?.removeWebSocketConnectListener(
            webSocketConnectListener
        )
        enterAffectiveCloudManager?.removeWebSocketDisconnectListener(
            websocketDisconnectListener
        )
        meditationStatusPlayer?.release()
        super.onDestroy()
    }

}
