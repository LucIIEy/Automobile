package com.automobile.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class Situation {
    RESTROOM,
    REST,
    PARKING,
    FUEL,
    PHARMACY,
    CHARGING,
    HOSPITAL
}

data class Place(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val solves: Set<Situation>,
    val extraMin: Double,
    val entry: Double,
    val parking: Double,
    val safety: Double,
    val simple: Double,
    val reason: String
)

data class Suggestion(
    val place: Place,
    val distanceKm: Double,
    val minutes: Int
)

object PlaceEngine {

    const val DEMO_LAT = 37.2900
    const val DEMO_LNG = 127.1050

    // 시연용 데이터. 실제 POI 검색 결과가 아니다.
    private val places = listOf(
        Place(
            name = "죽전휴게소",
            address = "경기도 용인시 수지구 경부고속도로 서울방향",
            lat = 37.3325,
            lng = 127.1043,
            solves = setOf(
                Situation.RESTROOM,
                Situation.REST,
                Situation.FUEL,
                Situation.PARKING
            ),
            extraMin = 1.0,
            entry = 1.0,
            parking = 0.9,
            safety = 1.0,
            simple = 1.0,
            reason = "고속도로에서 바로 들어갈 수 있어요"
        ),
        Place(
            name = "수지 공원 공중화장실",
            address = "경기도 용인시 수지구",
            lat = 37.3105,
            lng = 127.0950,
            solves = setOf(Situation.RESTROOM),
            extraMin = 8.0,
            entry = 0.3,
            parking = 0.3,
            safety = 0.6,
            simple = 0.3,
            reason = "고속도로를 빠져나가야 해요"
        ),
        Place(
            name = "서울 만남의광장 휴게소",
            address = "서울특별시 서초구 양재대로",
            lat = 37.4597,
            lng = 127.0420,
            solves = setOf(
                Situation.RESTROOM,
                Situation.REST,
                Situation.FUEL,
                Situation.PARKING
            ),
            extraMin = 1.0,
            entry = 1.0,
            parking = 0.8,
            safety = 1.0,
            simple = 1.0,
            reason = "휴게소에서 잠시 쉬어 갈 수 있어요"
        ),
        Place(
            name = "죽전동 약국 — 시연용",
            address = "경기도 용인시 수지구 죽전동",
            lat = 37.3250,
            lng = 127.1090,
            solves = setOf(Situation.PHARMACY),
            extraMin = 7.0,
            entry = 0.4,
            parking = 0.5,
            safety = 0.6,
            simple = 0.5,
            reason = "나들목 인근 약국을 가정한 시연용 후보예요"
        )
    )

    fun rank(
        situation: Situation,
        urgent: Boolean,
        lat: Double,
        lng: Double,
        nearestFirst: Boolean = true
    ): List<Suggestion> {

        val weights = when (situation) {
            Situation.RESTROOM ->
                doubleArrayOf(0.45, 0.25, 0.20, 0.05, 0.05)

            Situation.REST ->
                doubleArrayOf(0.10, 0.15, 0.25, 0.30, 0.20)

            Situation.PARKING ->
                doubleArrayOf(0.20, 0.30, 0.15, 0.25, 0.10)

            Situation.FUEL, Situation.CHARGING ->
                doubleArrayOf(0.30, 0.30, 0.10, 0.10, 0.20)

            Situation.PHARMACY, Situation.HOSPITAL ->
                doubleArrayOf(0.35, 0.20, 0.25, 0.05, 0.15)
        }

        if (urgent) weights[0] *= 1.5

        val scored = places
            .filter { situation in it.solves }
            .map { place ->
                val km = distanceKm(lat, lng, place.lat, place.lng)

                // 직선거리와 시연용 가정에 따른 예상치.
                // 내비게이션이 계산한 실제 경로 시간은 아니다.
                val minutes = km / 90.0 * 60.0 + place.extraMin
                val timeScore = 1.0 - (minutes / 20.0).coerceAtMost(1.0)

                val score =
                    weights[0] * timeScore +
                            weights[1] * place.entry +
                            weights[2] * place.parking +
                            weights[3] * place.safety +
                            weights[4] * place.simple

                Suggestion(
                    place = place,
                    distanceKm = km,
                    minutes = minutes.roundToInt().coerceAtLeast(1)
                ) to score
            }

        return if (nearestFirst) {
            scored.sortedBy { it.first.distanceKm }
        } else {
            scored.sortedByDescending { it.second }
        }.map { it.first }
    }

    private fun distanceKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val a = (
                sin(dLat / 2) * sin(dLat / 2) +
                        cos(Math.toRadians(lat1)) *
                        cos(Math.toRadians(lat2)) *
                        sin(dLng / 2) * sin(dLng / 2)
                ).coerceIn(0.0, 1.0)

        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}