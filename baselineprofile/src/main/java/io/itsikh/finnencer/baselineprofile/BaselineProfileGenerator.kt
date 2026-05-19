package io.itsikh.finnencer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "io.itsikh.finnencer") {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("io.itsikh.finnencer").depth(0)), 5_000)

        val scrollable = device.findObject(By.scrollable(true))
        if (scrollable != null) {
            scrollable.setGestureMargin(device.displayWidth / 5)
            repeat(3) { scrollable.fling(Direction.DOWN) }
            repeat(3) { scrollable.fling(Direction.UP) }
        }
    }
}
