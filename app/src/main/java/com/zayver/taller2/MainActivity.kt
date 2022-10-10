package com.zayver.taller2

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.zayver.taller2.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setListeners()
    }
    private fun setListeners(){
        binding.imageImagebutton.setOnClickListener {
            val int = Intent(baseContext, ImageUploadActivity::class.java)
            startActivity(int)
        }
        binding.mapsImagebutton.setOnClickListener {
            val int = Intent(baseContext, MapActivity::class.java)
            startActivity(int)
        }
    }
}