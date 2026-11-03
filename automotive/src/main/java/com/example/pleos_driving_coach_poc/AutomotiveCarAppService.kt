package com.example.pleos_driving_coach_poc

import android.content.Intent
import androidx.car.app.Session
import com.example.pleos_driving_coach_poc.shared.MyCarAppService

class AutomotiveCarAppService : MyCarAppService() {
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent) = VehicleDataScreen(carContext)
    }
}
