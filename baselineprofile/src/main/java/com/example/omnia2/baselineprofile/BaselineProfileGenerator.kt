package com.example.omnia2.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = "com.example.omnia2",
            includeInStartupProfile = true
        ) {
            // Ici, on simule le démarrage de l'application
            // On peut ajouter des interactions si nécessaire
            pressHome()
            startActivityAndWait()
        }
    }
}
