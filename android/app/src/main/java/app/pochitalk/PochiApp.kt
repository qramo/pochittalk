package app.pochitalk

import android.app.Application
import app.pochitalk.engine.PochiEngine

class PochiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PochiEngine.init(this)
    }
}
