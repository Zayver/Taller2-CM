package com.zayver.taller2.network

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.RequestFuture
import com.android.volley.toolbox.Volley
import com.zayver.taller2.BuildConfig
import com.zayver.taller2.MapActivity
import com.zayver.taller2.entity.Location
import com.zayver.taller2.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.concurrent.ExecutionException

class NetworkRequester {
    companion object{
        //Coroutines for network calls
        internal suspend fun searchDir(dir:String, context: Context): List<Address> = withContext(Dispatchers.IO){
            withTimeout(MapActivity.COROUTINE_TIMEOUT) {
                val geocoder = Geocoder(context)
                return@withTimeout geocoder.getFromLocationName(dir, 1)
            }
        }
        internal suspend fun searchLocation(lat: Double, lon: Double, context: Context):
                List<Address> = withContext(Dispatchers.IO){
            withTimeout(MapActivity.COROUTINE_TIMEOUT){
                val geocoder = Geocoder(context)
                return@withTimeout geocoder.getFromLocation(lat, lon, 1)
            }
        }
        internal suspend fun drawRoute(start: GeoPoint, end: GeoPoint, roadManager: RoadManager):
                Road = withContext(Dispatchers.IO) {
            withTimeout(MapActivity.COROUTINE_TIMEOUT){
                val findRoute = arrayListOf(start, end)
                return@withTimeout roadManager.getRoad(findRoute)
            }
        }
        internal suspend fun drawRouteFromInternal(file: File, roadManager: RoadManager):
                Pair<Road, List<Location>> = withContext(Dispatchers.IO){
            val locationsList = StorageManager.retrieveInternalLocations(file)
            val transformed = ArrayList<GeoPoint>()
            for(i in locationsList){
                transformed.add(GeoPoint(i.latitude, i.longitude))
            }
            withTimeout(MapActivity.COROUTINE_TIMEOUT){
                return@withTimeout Pair(roadManager.getRoad(transformed), locationsList)
            }
        }
        internal suspend fun drawRouteFromInternalOtherApi(file:File, context: Context):
                Pair<JSONObject?, List<Location>> = withContext(Dispatchers.IO){
            val locationsList = StorageManager.retrieveInternalLocations(file)
            val transformed = ArrayList<GeoPoint>()
            val asker = JSONArray()
            for(i in locationsList){
                transformed.add(GeoPoint(i.latitude, i.longitude))
                val coordinates = JSONArray()
                coordinates.put(i.longitude)
                coordinates.put(i.latitude)
                asker.put(coordinates)
            }
            val request = JSONObject()
            val future = RequestFuture.newFuture<JSONObject>()
            request.put("coordinates", asker)
            withTimeout(MapActivity.COROUTINE_TIMEOUT) {
                val query = object : JsonObjectRequest(
                    Method.POST, MapActivity.OPEN_ROUTE_API, request,
                    future, future
                ){
                    override fun getHeaders(): MutableMap<String, String> {
                        val headers = HashMap<String, String>()
                        headers["Accept"] =
                            "application/json, application/geo+json, application/gpx+xml, img/png; charset=utf-8"
                        headers["Authorization"] = BuildConfig.ORS_API_KEY
                        headers["Content-Type"] = "application/json; charset=utf-8"
                        return headers
                    }
                }
                val requestQueue = Volley.newRequestQueue(context)
                requestQueue.add(query)
                requestQueue.start()
            }
            val response = try {
                 future.get()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: ExecutionException) {
                throw e
            }
            return@withContext Pair(response as JSONObject, locationsList)
        }
    }
}