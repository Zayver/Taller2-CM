package com.zayver.taller2.storage

import com.zayver.taller2.entity.Location
import com.zayver.taller2.location.PositionTracker
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.*

class StorageManager {
    companion object{
        //Manage private json
        fun saveLocation(file: File, pos: PositionTracker) {
            val input: String
            try {
                input = file.bufferedReader().use {
                    it.readText()
                }
            } catch (e: FileNotFoundException) {
                val obj = JSONObject()
                val arr = JSONArray()
                val clock = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                arr.put(
                    Location(
                        pos.position.latitude,
                        pos.position.longitude,
                        clock.toString()
                    ).toJson()
                )
                obj.put("positions", arr)
                file.bufferedWriter().use {
                    it.write(obj.toString(4))
                }
                return
            }
            val json = JSONObject(input)
            val arr = json.getJSONArray("positions")
            val clock = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            arr.put(
                Location(
                    pos.position.latitude,
                    pos.position.longitude,
                    clock.toString()
                ).toJson()
            )
            file.bufferedWriter().use {
                it.write(json.toString(4))
            }
        }

        fun retrieveInternalLocations(file: File): List<Location> {
            val input: String
            file.bufferedReader().use {
                input = it.readText()
            }
            val json = JSONObject(input)
            val arr = json.getJSONArray("positions")
            val list = LinkedList<Location>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    Location(
                        obj.getDouble("latitude"), obj.getDouble("longitude"),
                        obj.getString("date")
                    )
                )
            }
            return list
        }

        fun checkFileSystem(file: File) {
            if (file.exists())
                file.delete()
        }
    }
}