package com.pank.vsevodnom.ui.money

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pank.vsevodnom.data.Category
import com.pank.vsevodnom.ui.theme.Pal
import org.json.JSONObject

/**
 * База иконок категорий: Tabler Icons (MIT, © Paweł Kuna), 275 штук в группах как в Sumlio.
 * Хранится в assets/icons/tabler.json: имя → контуры SVG; рисуются линией 2 из 24.
 * В Category.icon ключ «t:имя»; старые ключи из CAT_ICONS тоже работают.
 */
object TablerIcons {
    @Volatile private var data: Pair<List<Pair<String, List<String>>>, Map<String, List<String>>>? = null
    private val cache = HashMap<String, ImageVector>()

    fun load(ctx: Context): Pair<List<Pair<String, List<String>>>, Map<String, List<String>>> {
        data?.let { return it }
        val r: Pair<List<Pair<String, List<String>>>, Map<String, List<String>>> = try {
            val o = JSONObject(ctx.assets.open("icons/tabler.json").bufferedReader().readText())
            val ic = o.getJSONObject("icons"); val icons = HashMap<String, List<String>>()
            ic.keys().forEach { k -> val a = ic.getJSONArray(k); icons[k] = (0 until a.length()).map { a.getString(it) } }
            val g = o.getJSONArray("groups")
            val groups = (0 until g.length()).map { i -> val x = g.getJSONObject(i); val arr = x.getJSONArray("icons"); x.getString("name") to (0 until arr.length()).map { arr.getString(it) } }
            groups to icons
        } catch (e: Exception) { emptyList<Pair<String, List<String>>>() to emptyMap() }
        data = r
        return r
    }

    fun vector(ctx: Context, name: String): ImageVector? = synchronized(cache) {
        cache[name] ?: run {
            val paths = load(ctx).second[name] ?: return null
            val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            paths.forEach { d ->
                runCatching { b.addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
            }
            b.build().also { cache[name] = it }
        }
    }
}

/** Палитра как в Sumlio: 19 оттенков × 8 насыщенностей (Material 200…900). */
val PALETTE: List<List<Color>> = listOf(
    "EF9A9A E57373 EF5350 F44336 E53935 D32F2F C62828 B71C1C", "F48FB1 F06292 EC407A E91E63 D81B60 C2185B AD1457 880E4F",
    "CE93D8 BA68C8 AB47BC 9C27B0 8E24AA 7B1FA2 6A1B9A 4A148C", "B39DDB 9575CD 7E57C2 673AB7 5E35B1 512DA8 4527A0 311B92",
    "9FA8DA 7986CB 5C6BC0 3F51B5 3949AB 303F9F 283593 1A237E", "90CAF9 64B5F6 42A5F5 2196F3 1E88E5 1976D2 1565C0 0D47A1",
    "81D4FA 4FC3F7 29B6F6 03A9F4 039BE5 0288D1 0277BD 01579B", "80DEEA 4DD0E1 26C6DA 00BCD4 00ACC1 0097A7 00838F 006064",
    "80CBC4 4DB6AC 26A69A 009688 00897B 00796B 00695C 004D40", "A5D6A7 81C784 66BB6A 4CAF50 43A047 388E3C 2E7D32 1B5E20",
    "C5E1A5 AED581 9CCC65 8BC34A 7CB342 689F38 558B2F 33691E", "E6EE9C DCE775 D4E157 CDDC39 C0CA33 AFB42B 9E9D24 827717",
    "FFF59D FFF176 FFEE58 FFEB3B FDD835 FBC02D F9A825 F57F17", "FFE082 FFD54F FFCA28 FFC107 FFB300 FFA000 FF8F00 FF6F00",
    "FFCC80 FFB74D FFA726 FF9800 FB8C00 F57C00 EF6C00 E65100", "FFAB91 FF8A65 FF7043 FF5722 F4511E E64A19 D84315 BF360C",
    "BCAAA4 A1887F 8D6E63 795548 6D4C41 5D4037 4E342E 3E2723", "E0E0E0 BDBDBD 9E9E9E 757575 616161 424242 303030 212121",
    "B0BEC5 90A4AE 78909C 607D8B 546E7A 455A64 37474F 263238",
).map { row -> row.split(' ').map { Color(0xFF000000 or it.toLong(16)) } }

/** Цвет категории: свой (из палитры) или старый по номеру. */
fun finColor(c: Category?): Color = if (c != null && c.color != 0) Color(c.color) else catColor(c)

@Composable
fun finIcon(c: Category?): ImageVector {
    val ctx = LocalContext.current
    val key = c?.icon.orEmpty()
    return if (key.startsWith("t:")) remember(key) { TablerIcons.vector(ctx, key.removePrefix("t:")) } ?: catIcon(c) else catIcon(c)
}

/** Значок категории: иконка цветом категории на светлой подложке того же цвета. */
@Composable
fun FinBadge(c: Category?, size: Dp = 44.dp) {
    val col = finColor(c)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(col.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(finIcon(c), null, tint = col, modifier = Modifier.size(size * 0.56f))
    }
}

/** Выбор иконки: группы как в Sumlio. */
@Composable
fun IconPicker(selected: String, color: Color, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val groups = remember { TablerIcons.load(ctx).first }
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        groups.forEach { (g, names) ->
            item(key = "g$g") { Text(g, style = MaterialTheme.typography.labelLarge, color = Pal.Muted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
            items(names.chunked(6), key = { "r$g${it.first()}" }) { row ->
                // клетки делят ширину поровну (раньше 6×44 dp не влезали в окно — сетка обрезалась справа)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    row.forEach { n ->
                        val sel = selected == "t:$n"
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) color.copy(alpha = 0.18f) else Pal.Card)
                            .border(1.dp, if (sel) color else Pal.Line, RoundedCornerShape(12.dp)).clickable { onPick("t:$n") }, contentAlignment = Alignment.Center) {
                            TablerIcons.vector(ctx, n)?.let { Icon(it, n, tint = if (sel) color else Pal.Ink, modifier = Modifier.size(24.dp)) }
                        }
                    }
                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Выбор цвета: сетка палитры, листается по горизонтали. */
@Composable
fun ColorPicker(selected: Color, onPick: (Color) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        items(PALETTE.first().indices.toList()) { shade ->
            Column {
                PALETTE.forEach { row ->
                    val c = row[shade]
                    Box(Modifier.size(38.dp).background(c).clickable { onPick(c) }, contentAlignment = Alignment.Center) {
                        if (c == selected) Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}
