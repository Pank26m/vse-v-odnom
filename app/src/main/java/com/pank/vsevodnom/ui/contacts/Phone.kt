package com.pank.vsevodnom.ui.contacts

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.ui.sms.SmsScreen
import com.pank.vsevodnom.ui.theme.Pal

/** «Телефон»: контакты и СМС в одном разделе — мини-вкладки сверху, «Набрать» открывает звонилку. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PhoneScreen(onBack: () -> Unit, start: Int = 0) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(start) }
    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun chip(i: Int, ic: ImageVector, t: String) {
                val sel = tab == i
                Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) Pal.Sky else Pal.Card).clickable { tab = i }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, tint = if (sel) Pal.SkyInk else Pal.Ink, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(t, style = MaterialTheme.typography.labelLarge, color = if (sel) Pal.SkyInk else Pal.Ink)
                }
            }
            chip(0, Icons.Rounded.Person, "Контакты")
            chip(1, Icons.Rounded.Sms, "СМС")
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Pal.Mint).clickable {
                runCatching { ctx.startActivity(Intent(ctx, com.pank.vsevodnom.call.DialerActivity::class.java)) }
            }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Dialpad, "Набрать", tint = Pal.MintInk, modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
            if (tab == 0) ContactsScreen(onBack) else SmsScreen(onBack)
        }
    }
}
