package com.zayver.taller2.location

import org.osmdroid.util.GeoPoint
import kotlin.math.*

class PositionTracker{
    companion object{
        const val RADIUS_OF_EARTH_KM = 6371L
        const val DISTANCE_REPORT = 3e-2
    }
    var position = GeoPoint(0.0,0.0)
    var initialized = false

    /**
     * @return true if the difference between new pos is at least DISTANCE_REPORT
     */
    fun updateLocation(newPosition: GeoPoint):Boolean{
        if (!initialized)
            initialized = true
        return if(calculateDistance(position.latitude, position.longitude, newPosition.latitude, newPosition.longitude) >= DISTANCE_REPORT){
            position = newPosition
            true
        } else{
            false
        }
    }
    private fun calculateDistance(lat1: Double, long1: Double, lat2: Double, long2: Double): Double {
        val latDistance = Math.toRadians(lat1 - lat2)
        val lngDistance = Math.toRadians(long1 - long2)
        val a = (sin(latDistance / 2) * sin(latDistance / 2)
                + (cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2))
                * sin(lngDistance / 2) * sin(lngDistance / 2)))
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val result: Double = RADIUS_OF_EARTH_KM * c
        return (result * 100.0).roundToInt() / 100.0
    }
}