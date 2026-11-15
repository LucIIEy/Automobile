package com.automobile.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

class MainActivity : AppCompatActivity() {

    private val tag = "EmergencyGuide"

    private val locationPermission = "pleos.car.permission.FUSED_LOCATION"
    private val allPermissionRequest = 100
    private val microphonePermissionRequest = 101

    private lateinit var statusText: TextView
    private lateinit var mainText: TextView
    private lateinit var subText: TextView
    private lateinit var micButton: MaterialButton
    private lateinit var voiceTab: TextView
    private lateinit var choiceTab: TextView
    private lateinit var sortButton: TextView

    private lateinit var card: View
    private lateinit var placeName: TextView
    private lateinit var placeMeta: TextView
    private lateinit var placeReason: TextView

    private lateinit var stepOne: View
    private lateinit var stepTwo: View
    private lateinit var stepThree: View

    private lateinit var tts: TextToSpeech
    private lateinit var stt: SpeechToText
    private lateinit var navi: NaviHelper
    private lateinit var fused: FusedLocation

    private var ttsReady = false
    private var sttReady = false
    private var naviReady = false
    private var locationStarted = false

    private var isListening = false
    private var listenAfterSpeak = false

    // 이전 음성 인식의 늦은 콜백이 새 요청을 덮어쓰지 않게 구분한다.
    private var recognitionGeneration = 0
    private var activeRecognitionListener: ResultListener? = null

    private var curLat = PlaceEngine.DEMO_LAT
    private var curLng = PlaceEngine.DEMO_LNG

    private var waitingAnswer = false
    private var nearestFirst = true
    private var candidates: List<Suggestion> = emptyList()
    private var candidateIndex = 0
    private var lastRequest = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        mainText = findViewById(R.id.mainText)
        subText = findViewById(R.id.subText)
        micButton = findViewById(R.id.micButton)
        voiceTab = findViewById(R.id.voiceTab)
        choiceTab = findViewById(R.id.choiceTab)
        sortButton = findViewById(R.id.sortButton)

        card = findViewById(R.id.card)
        placeName = findViewById(R.id.placeName)
        placeMeta = findViewById(R.id.placeMeta)
        placeReason = findViewById(R.id.placeReason)

        stepOne = findViewById(R.id.stepOne)
        stepTwo = findViewById(R.id.stepTwo)
        stepThree = findViewById(R.id.stepThree)

        voiceTab.isSelected = true
        choiceTab.isSelected = false
        stepOne.isSelected = true

        // 시스템 바와 앱 내용이 겹치지 않게 기존 여백에 인셋을 더한다.
        val root = findViewById<View>(R.id.main)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)

        micButton.setOnClickListener {
            if (isListening) {
                stopListening()
                setStatus("듣기 취소")
                subText.visibility = View.GONE
            } else {
                startListening()
            }
        }

        voiceTab.setOnClickListener {
            voiceTab.isSelected = true
            choiceTab.isSelected = false
            micButton.requestFocus()
        }

        choiceTab.setOnClickListener {
            listenAfterSpeak = false
            stopListening()

            if (::tts.isInitialized) {
                runCatching { tts.stop() }
            }

            voiceTab.isSelected = false
            choiceTab.isSelected = true
            subText.visibility = View.GONE
            findViewById<View>(R.id.quickToilet).requestFocus()
            setStatus("항목을 선택해 주세요")
        }

        findViewById<View>(R.id.quickToilet).setOnClickListener {
            voiceTab.isSelected = false
            choiceTab.isSelected = true

            handleSpeech("화장실 급해")
        }

        findViewById<View>(R.id.quickFuel).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("주유 · 충전소")
                .setItems(arrayOf("주유소", "전기차 충전소")) { _, which ->
                    handleSpeech(
                        if (which == 0) "주유소 찾아줘"
                        else "충전소 찾아줘"
                    )
                }
                .show()
        }

        findViewById<View>(R.id.quickRest).setOnClickListener {
            voiceTab.isSelected = false
            choiceTab.isSelected = true

            handleSpeech("좀 쉬고 싶어")
        }

        findViewById<View>(R.id.yesButton).setOnClickListener {
            listenAfterSpeak = false
            stopListening()
            if (::tts.isInitialized) runCatching { tts.stop() }
            handleAnswer("응")
        }

        findViewById<View>(R.id.noButton).setOnClickListener {
            listenAfterSpeak = false
            stopListening()
            if (::tts.isInitialized) runCatching { tts.stop() }
            handleAnswer("아니")
        }

        findViewById<View>(R.id.helpButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("사용 방법")
                .setMessage(
                    "1. 마이크를 누르고 필요한 것을 말해 주세요.\n" +
                            "2. 또는 오른쪽 항목을 선택하세요.\n" +
                            "3. 제안된 장소를 확인하고 안내를 요청하세요.\n\n" +
                            "“쉬 마려워”는 화장실,\n" +
                            "“쉬고 싶어”는 휴식 장소를 찾습니다.\n\n" +
                            "현재 장소 목록은 시연용 데이터입니다."
                )
                .setPositiveButton("확인", null)
                .show()
        }

        findViewById<View>(R.id.routeButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("시연용 목적지 설정")
                .setMessage(
                    "내비게이션 목적지를 42dot으로 설정합니다.\n" +
                            "목적지가 설정된 상태에서 제안 장소를 경유지로 추가할 수 있습니다."
                )
                .setPositiveButton("설정") { _, _ ->
                    setDemoDestination()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        sortButton.setOnClickListener {
            nearestFirst = !nearestFirst
            sortButton.text = if (nearestFirst) "근처 순" else "추천 순"

            if (waitingAnswer && lastRequest.isNotBlank()) {
                handleSpeech(lastRequest)
            }
        }

        setupTts()
        setupStt()
        setupNavi()

        val missing = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            locationPermission
        ).filter {
            ContextCompat.checkSelfPermission(this, it) !=
                    PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            setupLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                allPermissionRequest
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            ContextCompat.checkSelfPermission(this, locationPermission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            setupLocation()
        }

        if (requestCode == microphonePermissionRequest) {
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startListening()
            } else {
                setStatus("마이크 권한이 필요해요")
            }
        }
    }

    // 음성 안내

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onStop() = Unit

        override fun onDone() {
            runOnUiThread {
                if (
                    !isFinishing &&
                    !isDestroyed &&
                    listenAfterSpeak
                ) {
                    listenAfterSpeak = false
                    startListening()
                }
            }
        }

        override fun onError(errorType: TtsError) {
            Log.e(tag, "TTS error: $errorType")
            runOnUiThread {
                listenAfterSpeak = false
                setStatus("음성 안내 오류")
            }
        }
    }

    private fun setupTts() {
        try {
            tts = TextToSpeech(applicationContext, TtsMode.ON_DEVICE)

            tts.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        tts.addEventListener(ttsListener)
                        tts.setLocale(Locale.KOREA)
                        ttsReady = true
                    }
                }

                override fun onFailure(errorType: TtsError) {
                    Log.e(tag, "TTS initialize: $errorType")
                    runOnUiThread { ttsReady = false }
                }
            })
        } catch (e: Exception) {
            Log.e(tag, "TTS setup", e)
            ttsReady = false
        }
    }

    private fun speak(
        text: String,
        show: String = text,
        thenListen: Boolean = false
    ) {
        mainText.text = show
        listenAfterSpeak = false

        if (!ttsReady) {
            if (thenListen && sttReady) startListening()
            return
        }

        try {
            tts.stop()
            listenAfterSpeak = thenListen
            tts.speak(text)
        } catch (e: Exception) {
            listenAfterSpeak = false
            Log.e(tag, "TTS speak", e)
        }
    }

    // 음성 인식

    private fun setupStt() {
        try {
            stt = SpeechToText(applicationContext, SttMode.ON_DEVICE)

            stt.initialize(object : SttInitListener {
                override fun onSuccess() {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        stt.setLocale(Locale.KOREA)
                        sttReady = true
                        setStatus("준비 완료")
                    }
                }

                override fun onFailure(errorType: SttError) {
                    Log.e(tag, "STT initialize: $errorType")
                    runOnUiThread {
                        sttReady = false
                        setStatus("음성 인식 준비 실패")
                    }
                }
            })
        } catch (e: Exception) {
            sttReady = false
            setStatus("음성 인식을 사용할 수 없어요")
            Log.e(tag, "STT setup", e)
        }
    }

    private fun startListening() {
        if (isListening) return

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphonePermissionRequest
            )
            return
        }

        if (!sttReady) {
            setStatus("음성 인식이 준비되지 않았어요")
            return
        }

        stopListening()
        listenAfterSpeak = false

        if (::tts.isInitialized) {
            runCatching { tts.stop() }
        }

        val generation = ++recognitionGeneration

        val listener = object : ResultListener {
            override fun onReady() = Unit

            override fun onStartedRecognition() {
                runOnUiThread {
                    if (
                        isDestroyed ||
                        generation != recognitionGeneration
                    ) return@runOnUiThread

                    setStatus("듣고 있어요")
                }
            }

            override fun onUpdated(stt: String, completed: Boolean) {
                runOnUiThread {
                    if (
                        isDestroyed ||
                        generation != recognitionGeneration
                    ) return@runOnUiThread

                    if (completed) {
                        handleSpeech(stt)
                    } else if (stt.isNotBlank()) {
                        subText.text = stt
                        subText.visibility = View.VISIBLE
                    }
                }
            }

            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit

            override fun onEndedRecognition() {
                runOnUiThread {
                    if (
                        isDestroyed ||
                        generation != recognitionGeneration
                    ) return@runOnUiThread

                    // 최종 결과가 뒤늦게 도착할 수 있어 여기서는
                    // generation을 변경하거나 리스너를 제거하지 않는다.
                    isListening = false
                    micButton.text = "눌러서 말하기"
                    stepOne.isSelected = !waitingAnswer
                    stepTwo.isSelected = false
                    stepThree.isSelected = waitingAnswer
                    setStatus("준비 완료")
                }
            }

            override fun onError(errorType: SttError) {
                runOnUiThread {
                    if (
                        isDestroyed ||
                        generation != recognitionGeneration
                    ) return@runOnUiThread

                    stopListening()
                    subText.text = "다시 눌러 말씀해 주세요."
                    subText.visibility = View.VISIBLE
                    setStatus("음성 인식 오류")
                    Log.e(tag, "STT error: $errorType")
                }
            }
        }

        activeRecognitionListener = listener

        try {
            stt.addListener(listener)
            isListening = true

            voiceTab.isSelected = true
            choiceTab.isSelected = false

            micButton.text = "듣기 취소"
            subText.text = if (waitingAnswer) {
                "응 또는 아니라고 말씀해 주세요."
            } else {
                "말씀을 듣고 있어요."
            }
            subText.visibility = View.VISIBLE

            stepOne.isSelected = false
            stepTwo.isSelected = true
            stepThree.isSelected = false

            setStatus("듣고 있어요")
            stt.request()
        } catch (e: Exception) {
            stopListening()
            setStatus("음성 인식을 시작하지 못했어요")
            Log.e(tag, "STT request", e)
        }
    }

    private fun stopListening() {
        recognitionGeneration++
        isListening = false

        val listener = activeRecognitionListener
        activeRecognitionListener = null

        if (::stt.isInitialized && listener != null) {
            runCatching { stt.removeListener(listener) }
            runCatching { stt.stop() }
        }

        micButton.text = "눌러서 말하기"
        stepOne.isSelected = !waitingAnswer
        stepTwo.isSelected = false
        stepThree.isSelected = waitingAnswer
    }

    // 요청 분류

    private fun handleSpeech(text: String) {
        listenAfterSpeak = false
        stopListening()

        if (::tts.isInitialized) {
            runCatching { tts.stop() }
        }

        val input = text
            .lowercase(Locale.KOREA)
            .replace(Regex("[^가-힣a-zA-Z0-9]"), "")

        if (input.isBlank()) {
            speak(
                "잘 못 들었어요. 다시 말씀해 주세요.",
                show = "다시 말씀해 주세요."
            )
            return
        }

        if (input == "쉬") {
            speak(
                "화장실이 필요하세요, 아니면 잠깐 쉬고 싶으세요?",
                show = "화장실인가요,\n휴식인가요?",
                thenListen = true
            )
            return
        }

        val restroomWords = listOf(
            "화장실", "소변", "대변", "오줌", "똥",
            "쉬하고싶", "쉬해야", "쉬하러", "쉬할래"
        )

        val restWords = listOf(
            "쉬고싶", "쉬어가", "쉬어야", "쉬었다",
            "쉬자", "쉴곳", "쉴데", "쉴래",
            "좀쉬", "잠깐쉬", "잠시쉬",
            "휴식", "휴게소", "졸려", "졸리", "졸음", "피곤"
        )

        val situation = when {
            restroomWords.any { input.contains(it) } ||
                    (input.contains("쉬") && input.contains("마려")) ->
                Situation.RESTROOM

            input.contains("충전소") ||
                    input.contains("전기차충전") ->
                Situation.CHARGING

            input.contains("주유") || input.contains("기름") ->
                Situation.FUEL

            input.contains("병원") || input.contains("응급실") ->
                Situation.HOSPITAL

            listOf("약국", "멀미", "두통").any {
                input.contains(it)
            } ->
                Situation.PHARMACY

            restWords.any { input.contains(it) } ->
                Situation.REST

            input.contains("주차") ->
                Situation.PARKING

            else ->
                null
        }

        // 확인 대기 중이어도 새로운 장소 요청이 있으면 우선 처리한다.
        if (situation == null) {
            if (waitingAnswer) {
                handleAnswer(input)
            } else {
                speak(
                    "필요한 장소를 다시 말씀해 주세요.",
                    show = "어디가 필요하세요?"
                )
            }
            return
        }

        lastRequest = text
        waitingAnswer = false

        val urgent = listOf("급", "빨리", "당장").any {
            input.contains(it)
        }

        candidates = PlaceEngine.rank(
            situation = situation,
            urgent = urgent,
            lat = curLat,
            lng = curLng,
            nearestFirst = nearestFirst
        )
        candidateIndex = 0

        if (candidates.isEmpty()) {
            resetConversation()
            subText.text = "현재 시연용 목록에는 해당 장소가 없어요."
            subText.visibility = View.VISIBLE
            speak(
                "현재 등록된 후보가 없어요.",
                show = "등록된 후보가 없어요."
            )
            return
        }

        suggestCurrent()
    }

    private fun suggestCurrent() {
        val suggestion = candidates.getOrNull(candidateIndex) ?: return
        val km = String.format(
            Locale.KOREA,
            "%.1f",
            suggestion.distanceKm
        )

        waitingAnswer = true

        placeName.text = suggestion.place.name
        placeMeta.text =
            "약 ${km}km · 예상 ${suggestion.minutes}분 · 시연 데이터"
        placeReason.text = suggestion.place.reason

        card.visibility = View.VISIBLE
        subText.visibility = View.GONE

        stepOne.isSelected = false
        stepTwo.isSelected = false
        stepThree.isSelected = true

        setStatus("장소를 확인해 주세요")

        speak(
            "${suggestion.place.name}입니다. " +
                    "${suggestion.place.reason}. 여기로 안내할까요?",
            show = "여기로\n안내할까요?",
            thenListen = true
        )
    }

    private fun handleAnswer(text: String) {
        if (!waitingAnswer) return

        val input = text.replace(Regex("[^가-힣a-zA-Z0-9]"), "")

        val yes = input in setOf(
            "응", "어", "네", "예", "그래", "좋아", "좋아요",
            "가자", "응가자", "네가자", "응좋아",
            "거기로가자", "거기로가줘", "안내해줘",
            "안내시작", "가주세요"
        )

        val no = input.startsWith("아니") ||
                listOf("싫", "다른곳", "다른데", "말고").any {
                    input.contains(it)
                }

        when {
            no -> {
                candidateIndex++

                if (candidateIndex < candidates.size) {
                    suggestCurrent()
                } else {
                    resetConversation()
                    speak(
                        "다른 후보가 없어요. 다시 요청해 주세요.",
                        show = "다른 후보가 없어요."
                    )
                }
            }

            yes -> {
                val place = candidates
                    .getOrNull(candidateIndex)
                    ?.place ?: return

                if (addWaypoint(place)) {
                    resetConversation()
                    speak(
                        "${place.name}의 경유지 추가를 요청했어요.",
                        show = "경유지 추가를\n요청했어요."
                    )
                }
            }

            else -> {
                speak(
                    "안내할까요? 응 또는 아니라고 말씀해 주세요.",
                    show = "여기로\n안내할까요?",
                    thenListen = true
                )
            }
        }
    }

    private fun resetConversation() {
        waitingAnswer = false
        listenAfterSpeak = false
        candidates = emptyList()
        candidateIndex = 0
        card.visibility = View.GONE
        subText.visibility = View.GONE

        stepOne.isSelected = true
        stepTwo.isSelected = false
        stepThree.isSelected = false
    }

    // 내비게이션

    private val naviListener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() {
            setStatus("경로 반영 완료")
        }

        override fun onError(errorCode: NaviErrorCode) {
            setStatus("경로 요청 실패")
            Log.e(tag, "Navigation error: $errorCode")
        }
    }

    private fun setupNavi() {
        try {
            navi = NaviHelper(applicationContext)
            navi.initialize(
                onConnected = {
                    navi.addListener(naviListener)
                    runOnUiThread { naviReady = true }
                },
                onFailed = { error ->
                    runOnUiThread { naviReady = false }
                    Log.e(tag, "Navigation initialize: ${error.message}")
                }
            )
        } catch (e: Exception) {
            naviReady = false
            Log.e(tag, "Navigation setup", e)
        }
    }

    private fun setDemoDestination() {
        if (!naviReady) {
            setStatus("내비게이션 연결을 확인해 주세요")
            return
        }

        try {
            navi.requestRoute(
                RouteInfo(
                    latitude = 37.48544722,
                    longitude = 127.03636666,
                    displayLongitude = null,
                    displayLatitude = null,
                    poiName = "42dot",
                    poiId = "36khp37hh8gqcm5nqhe5",
                    poiSubId = "0",
                    address = "서울시 강남구 남부순환로 2621"
                )
            )
            setStatus("목적지 설정 요청")
        } catch (e: Exception) {
            setStatus("목적지를 설정하지 못했어요")
            Log.e(tag, "Request route", e)
        }
    }

    private fun addWaypoint(place: Place): Boolean {
        if (!naviReady) {
            setStatus("내비게이션 연결을 확인해 주세요")
            return false
        }

        return try {
            navi.addWaypoint(
                RequestWaypointInfo(
                    latitude = place.lat,
                    longitude = place.lng,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    poiName = place.name,
                    poiSubId = "0",
                    address = place.address
                )
            )
            setStatus("경유지 추가 요청")
            true
        } catch (e: Exception) {
            setStatus("경유지를 추가하지 못했어요")
            Log.e(tag, "Add waypoint", e)
            false
        }
    }

    // 위치

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            runOnUiThread {
                curLat = location.latitude
                curLng = location.longitude
            }
        }

        override fun onError(exception: Exception) {
            Log.e(tag, "Location error", exception)
        }
    }

    private fun setupLocation() {
        if (locationStarted) return

        try {
            if (!::fused.isInitialized) {
                fused = FusedLocation(applicationContext)
            }

            if (fused.isSupported()) {
                fused.initialize()
                fused.registerFusedLocationCallback(locationListener)
                locationStarted = true
            }
        } catch (e: Exception) {
            Log.e(tag, "Location setup", e)
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            if (!isDestroyed) {
                statusText.text = text
            }
        }
    }

    override fun onDestroy() {
        listenAfterSpeak = false
        recognitionGeneration++
        isListening = false

        if (::stt.isInitialized) {
            activeRecognitionListener?.let { listener ->
                runCatching { stt.removeListener(listener) }
            }
            runCatching { stt.stop() }
            runCatching { stt.release() }
        }

        if (::tts.isInitialized) {
            runCatching { tts.removeEventListener(ttsListener) }
            runCatching { tts.stop() }
            runCatching { tts.release() }
        }

        if (::navi.isInitialized) {
            runCatching { navi.removeListener(naviListener) }
            runCatching { navi.release() }
        }

        if (::fused.isInitialized) {
            if (locationStarted) {
                runCatching {
                    fused.unregisterFusedLocationCallback(locationListener)
                }
            }
            runCatching { fused.release() }
        }

        super.onDestroy()
    }
}