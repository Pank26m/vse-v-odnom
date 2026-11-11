/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.goodwy.keyboard.app.settings.about

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goodwy.keyboard.R
import com.goodwy.keyboard.lib.compose.FlorisScreen
import com.goodwy.keyboard.lib.compose.stringRes

/** Лицензии: в «Всё в одном» без плагина AboutLibraries — коротко текстом. */
@Composable
fun ThirdPartyLicensesScreen() = FlorisScreen {
    title = stringRes(R.string.about__third_party_licenses__title)
    iconSpaceReserved = false

    content {
        Text(
            "Клавиатура основана на Inkwell Keyboard (Goodwy) и FlorisBoard (Patrick Goldinger и участники), лицензия Apache 2.0.\n\n" +
                "Библиотеки: AndroidX и Jetpack Compose (Apache 2.0), Kotlin и kotlinx (Apache 2.0), JetPref (Apache 2.0), " +
                "cache4k (Apache 2.0), Compose Tooltip (Apache 2.0).\n\n" +
                "Полный текст лицензии Apache 2.0: https://www.apache.org/licenses/LICENSE-2.0",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
