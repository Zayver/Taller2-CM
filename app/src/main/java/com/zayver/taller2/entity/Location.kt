package com.zayver.taller2.entity

import org.json.JSONObject

data class Location(val latitude: Double, val longitude: Double, val date: String){
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("latitude", latitude)
        obj.put("longitude", longitude)
        obj.put("date",date)
        return obj
    }
}
