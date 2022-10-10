package com.zayver.taller2

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.zayver.taller2.databinding.ActivityMapBinding
import com.zayver.taller2.entity.Location
import com.zayver.taller2.location.PositionTracker
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.bonuspack.kml.KmlDocument
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.LinkedList


class MapActivity : AppCompatActivity() {

    companion object{
        const val SHORT_GPS_UPDATE_INTERVAL = 500L
        const val LONG_GPS_UPDATE_INTERVAL = 1000L
        const val MAP_ZOOM = 18.0
        const val JSON_FILENAME = "locations.json"
        const val LIGHT_SENSOR_UPPER_BOUND = 5000
        const val COROUTINE_TIMEOUT = 5000L
        const val OPEN_ROUTE_API = "https://api.openrouteservice.org/v2/directions/driving-car/geojson"
    }
    private lateinit var binding: ActivityMapBinding
    private val positionTracker = PositionTracker()
    private val requestGPSEnable = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ){
        if(it.resultCode == RESULT_OK){
            //requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            updatePositionOnMap()
        }
        else{
            Log.d("Mio", "GPS off")
        }
    }
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ){
        if(it){
            // Si se otorgó permiso de localización, mirar si el GPS está encendido
            checkLocationSettings()
        }
        else{
            Toast.makeText(applicationContext, "Se necesita permisos de localización", Toast.LENGTH_LONG).show()
            Log.d("Mio","Acceso a ubicacion la ubicacion denegado")
        }
    }

    private val mLocationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val actualPosition = GeoPoint(locationResult.lastLocation.latitude, locationResult.lastLocation.longitude)
            if(!positionTracker.initialized){
                binding.map.controller.setCenter(actualPosition)
                binding.map.controller.setZoom(MAP_ZOOM)
                positionMarker = setMarker(actualPosition, getString(R.string.your_pos))
                binding.myLocationButton.isVisible = true
                binding.otherApiImageButton.isVisible = true
                binding.resetImageButton.isVisible = true
                binding.drawImageButton.isVisible = true
            }
            if(positionTracker.updateLocation(actualPosition)){
                CoroutineScope(Dispatchers.IO).launch{
                    saveLocation()
                }
            }
        }
    }

    private lateinit var mFusedLocationClient: FusedLocationProviderClient
    private lateinit var mLocationRequest: LocationRequest

    private lateinit var sensorManager: SensorManager
    private lateinit var lightSensor: Sensor
    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                if(it.values[0] < LIGHT_SENSOR_UPPER_BOUND && !darkModeOn){
                    setDarkMode()
                    darkModeOn = true
                }
                else if (it.values[0] >= LIGHT_SENSOR_UPPER_BOUND){
                    setLightMode()
                    darkModeOn = false
                }
            }
        }
        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
    }
    private var darkModeOn = false

    private lateinit var positionMarker: Marker
    private var longPressedMarker: Marker? = null
    private var searchedMarker: Marker? = null
    private var roadOverlay: Polyline? = null
    private var roadMarkers = ArrayList<Marker>()
    private var folderOverlay: FolderOverlay? = null

    private lateinit var roadManager: RoadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkFileSystem()
        requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        Configuration.getInstance().load(baseContext, PreferenceManager.getDefaultSharedPreferences(baseContext))
        initMap()
        initLightSensor()
        setListeners()
        roadManager = OSRMRoadManager(this, "ANDROID")
    }
    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        createLocationRequest()
        sensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }
    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        stopLocationUpdates()
        sensorManager.unregisterListener(lightSensorListener)
    }
    //Set listeners helper
    private fun setListeners() {
        binding.searchBar.setOnEditorActionListener { textView: TextView, id: Int, _: KeyEvent? ->
            if(id == EditorInfo.IME_ACTION_SEND) {
                val dir = textView.text.toString()
                if (dir.isNotEmpty()) {
                    CoroutineScope(Dispatchers.Main).launch{
                        val resultDir: List<Address> = try{
                            searchDir(dir)
                        }catch (e: TimeoutCancellationException){
                            displayErrorText("Network Timeout")
                            return@launch
                        }catch (e: Exception){
                            displayErrorText("Error: $e")
                            return@launch
                        }
                        if(resultDir.isNotEmpty()){
                            clearMap()
                            val pos = GeoPoint(resultDir[0].latitude, resultDir[0].longitude)
                            binding.map.controller.animateTo(pos)
                            binding.map.controller.zoomTo(MAP_ZOOM)
                            searchedMarker = setMarker(pos, resultDir[0].getAddressLine(0), R.drawable.map_marker_blue)
                            launch{
                                try{
                                    val road = drawRoute(positionTracker.position, pos)
                                    drawRoad(road)
                                    displayRoadLength(road.mLength)
                                }catch (e: TimeoutCancellationException){
                                    displayErrorText("Network Timeout")
                                }catch (e: Exception){
                                    displayErrorText("Error: $e")
                                }
                            }
                        }
                        else {
                            Toast.makeText(
                                baseContext,
                                "Dirección no encontrada",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    return@setOnEditorActionListener true
                }
            }
            return@setOnEditorActionListener false
        }
        binding.myLocationButton.setOnClickListener {
            //binding.map.controller.setCenter(positionTracker.position)
            binding.map.overlays.remove(positionMarker)
            positionMarker = setMarker(positionTracker.position, getString(R.string.your_pos))
            binding.map.controller.zoomTo(MAP_ZOOM)
            binding.map.controller.animateTo(positionTracker.position)
        }
        binding.drawImageButton.setOnClickListener {
            if(roadOverlay != null){
                binding.map.overlays.clear()
            }
            CoroutineScope(Dispatchers.Main).launch {
                val (road, points) = try{
                     drawRouteFromInternal()
                }catch (e: TimeoutCancellationException){
                    displayErrorText("Network Timeout")
                    return@launch
                }catch (e: Exception){
                    displayErrorText("Error: $e")
                    return@launch
                }
                drawRoad(road)
                for(i in points){
                    roadMarkers.add(setMarker(GeoPoint(i.latitude, i.longitude), i.date))
                }
            }
        }
        binding.resetImageButton.setOnClickListener {
            clearMap()
            //checkFileSystem()
        }
        binding.otherApiImageButton.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val (_, points) = try{
                    clearMap()
                    drawRouteFromInternalOtherApi()
                }catch (e: TimeoutCancellationException){
                    displayErrorText("Network Timeout")
                    return@launch
                }catch (e: Exception){
                    displayErrorText("Error: $e")
                    return@launch
                }
                for(i in points){
                    roadMarkers.add(setMarker(GeoPoint(i.latitude, i.longitude), i.date))
                }
            }
        }
    }
    //manage map markets
    private fun setMarker(position: GeoPoint, title: String, icon: Int = R.drawable.map_marker): Marker{
        val marker = Marker(binding.map)
        marker.title = title
        val myIcon = AppCompatResources.getDrawable(baseContext, icon)
        marker.icon = myIcon
        marker.position = position
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        binding.map.overlays.add(marker)
        return marker
    }
    //Manage and update map
    private fun initMap(){
        val map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.controller.setZoom(MAP_ZOOM)
        val compassOverlay = CompassOverlay(baseContext, InternalCompassOrientationProvider(baseContext), map)
        compassOverlay.enableCompass()
        map.overlays.add(compassOverlay)
        map.overlays.add(createOverlayEvents())
    }
    private fun createOverlayEvents(): MapEventsOverlay {
        return MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                return false
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                longPressOnMap(p)
                return true
            }
        })
    }
    private fun longPressOnMap(p: GeoPoint) {
        CoroutineScope(Dispatchers.Main).launch {
            val dir: Address = try{
                 searchLocation(p.latitude, p.longitude)[0]
            }catch (e: TimeoutCancellationException){
                displayErrorText("Network Timeout")
                return@launch
            }catch (e: Exception){
                displayErrorText("Error: $e")
                return@launch
            }
            clearMap()
            longPressedMarker = setMarker(p, dir.getAddressLine(0), R.drawable.map_marker_green )
            binding.map.controller.zoomTo(MAP_ZOOM)
            binding.map.controller.animateTo(p)
            launch{
                try {
                    val road = drawRoute(positionTracker.position, p)
                    displayRoadLength(road.mLength)
                    drawRoad(road)
                }catch (e: TimeoutCancellationException){
                    displayErrorText("Network Timeout")
                }catch (e: Exception){
                    displayErrorText("Error: $e")
                }
            }
        }

    }
    private fun updatePositionOnMap(){
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(baseContext, Manifest.permission.ACCESS_FINE_LOCATION)
            -> {
                mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback,
                    Looper.getMainLooper())
            }
            else -> {
                Log.d("Mio", "Location suscripcion denied")
            }
        }
    }
    //Manage Localization updates
    private fun stopLocationUpdates(){
        mFusedLocationClient.removeLocationUpdates(mLocationCallback)
    }
    private fun createLocationRequest() {
        mLocationRequest = LocationRequest.create()
            .setInterval(LONG_GPS_UPDATE_INTERVAL)
            .setFastestInterval(SHORT_GPS_UPDATE_INTERVAL)
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
    }
    //Check GPS status
    private fun checkLocationSettings(){
        val builder = LocationSettingsRequest.Builder().addLocationRequest(mLocationRequest)
        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())
        task.addOnSuccessListener {
            updatePositionOnMap()
        }
        task.addOnFailureListener{
            if((it as ApiException).statusCode == CommonStatusCodes.RESOLUTION_REQUIRED){
                val resolvable = it as ResolvableApiException
                val isr = IntentSenderRequest.Builder(resolvable.resolution).build()
                requestGPSEnable.launch(isr)
            }else{
                Log.d("Mio", "No se ha activado el GPS")
            }
        }
    }
    //Manage private json
    private fun saveLocation() {
        val file = File(baseContext.getExternalFilesDir(null), JSON_FILENAME)
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
                    positionTracker.position.latitude,
                    positionTracker.position.longitude,
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
                positionTracker.position.latitude,
                positionTracker.position.longitude,
                clock.toString()
            ).toJson()
        )
        file.bufferedWriter().use {
            it.write(json.toString(4))
        }
    }
    private fun retrieveInternalLocations(): List<Location>{
        val file = File(baseContext.getExternalFilesDir(null), JSON_FILENAME)
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
                Location(obj.getDouble("latitude"), obj.getDouble("longitude"),
                    obj.getString("date")
                )
            )
        }
        return list
    }
    private fun checkFileSystem(){
        val file = File(baseContext.getExternalFilesDir(null), JSON_FILENAME)
        if(file.exists())
            file.delete()
    }
    //Light sensor manipulation
    private fun initLightSensor(){
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    }
    private fun setDarkMode(){
        binding.map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        binding.searchBar.setHintTextColor(ResourcesCompat.getColor(resources, R.color.grey_300, null))
        binding.searchBar.setTextColor(ResourcesCompat.getColor(resources, R.color.white, null))
        binding.searchBar.background = ResourcesCompat.getDrawable(resources, R.drawable.round_corner_dark, null)

    }
    private fun setLightMode(){
        binding.map.overlayManager.tilesOverlay.setColorFilter(null)
        binding.searchBar.setHintTextColor(ResourcesCompat.getColor(resources, R.color.grey_600, null))
        binding.searchBar.setTextColor(ResourcesCompat.getColor(resources, R.color.grey_900, null))
        binding.searchBar.background = ResourcesCompat.getDrawable(resources, R.drawable.round_corner, null)
    }
    //Road drawing
    private fun drawRoad(road: Road){
        if(roadOverlay!=null)
            binding.map.overlays.remove(roadOverlay)
        roadOverlay = RoadManager.buildRoadOverlay(road)
        roadOverlay?.let {
            it.outlinePaint.color = Color.RED
            it.outlinePaint.strokeWidth = 10F
        }
        binding.map.overlays.add(roadOverlay)
    }

    //Coroutines for network calls
    private suspend fun searchDir(dir:String): List<Address> = withContext(Dispatchers.IO){
        withTimeout(COROUTINE_TIMEOUT) {
            val geocoder = Geocoder(baseContext)
            return@withTimeout geocoder.getFromLocationName(dir, 1)
        }
    }
    private suspend fun searchLocation(lat: Double, lon: Double): List<Address> = withContext(Dispatchers.IO){
        withTimeout(COROUTINE_TIMEOUT){
            val geocoder = Geocoder(baseContext)
            return@withTimeout geocoder.getFromLocation(lat, lon, 1)
        }
    }
    private suspend fun drawRoute(start: GeoPoint, end: GeoPoint): Road = withContext(Dispatchers.IO) {
        withTimeout(COROUTINE_TIMEOUT){
            val findRoute = arrayListOf(start, end)
            return@withTimeout roadManager.getRoad(findRoute)
        }
    }
    private suspend fun drawRouteFromInternal(): Pair<Road, List<Location>> = withContext(Dispatchers.IO){
        val locationsList = retrieveInternalLocations()
        val transformed = ArrayList<GeoPoint>()
        for(i in locationsList){
            transformed.add(GeoPoint(i.latitude, i.longitude))
        }
        withTimeout(COROUTINE_TIMEOUT){
            return@withTimeout Pair(roadManager.getRoad(transformed), locationsList)
        }
    }
    private suspend fun drawRouteFromInternalOtherApi(): Pair<FolderOverlay, List<Location>> = withContext(Dispatchers.IO){
        val locationsList = retrieveInternalLocations()
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
        request.put("coordinates", asker)
        withTimeout(COROUTINE_TIMEOUT) {
            val query = object : JsonObjectRequest(Method.POST, OPEN_ROUTE_API, request,
                Response.Listener {
                    Log.d("Mio", "Response OK")
                    val kml = KmlDocument()
                    kml.parseGeoJSON(it.toString())
                    folderOverlay = kml.mKmlRoot.buildOverlay(binding.map, null, null, kml) as FolderOverlay
                    binding.map.postInvalidate()
                    binding.map.overlays.add(folderOverlay)

                },
                Response.ErrorListener {
                    displayErrorText("Error on Post request")
                }
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
            val requestQueue = Volley.newRequestQueue(baseContext)
            requestQueue.add(query)

            return@withTimeout Pair(FolderOverlay(), locationsList)
        }
    }

    //extras
    private fun displayRoadLength(distance: Double){
        Toast.makeText(baseContext, getString(R.string.distance_between_points, distance), Toast.LENGTH_LONG).show()
    }
    private fun displayErrorText(str: String){
        Toast.makeText(baseContext, str, Toast.LENGTH_LONG).show()
    }
    private fun clearMap(){
        val map = binding.map
        if(longPressedMarker != null)
            map.overlays.remove(longPressedMarker)
        if(roadOverlay != null)
            map.overlays.remove(roadOverlay)
        if(searchedMarker != null)
            map.overlays.remove(searchedMarker)
        if(roadMarkers.size > 0){
            roadMarkers.forEach {
                map.overlays.remove(it)
            }
            roadMarkers.clear()
        }
        if(folderOverlay != null)
            map.overlays.remove(folderOverlay)

    }

}
