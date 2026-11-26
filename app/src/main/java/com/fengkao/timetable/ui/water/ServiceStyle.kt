package com.fengkao.timetable.ui.water

import androidx.compose.ui.graphics.Color
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.BrandGreenSoft
import com.fengkao.timetable.ui.theme.IsDark

/** 校园服务的局部识别色；外框仍使用「泥石」品牌。 */
internal enum class ServiceKind {
    Drinking, Laundry;

    val action: Color get() = when (this) {
        Drinking -> BrandGreen
        Laundry -> Color(0xFF2F6F9F)
    }
    val ink: Color get() = if (!IsDark) action else when (this) {
        Drinking -> Color(0xFFA8CFFF)
        Laundry -> Color(0xFF9CC9EA)
    }
    val soft: Color get() = when (this) {
        Drinking -> BrandGreenSoft
        Laundry -> if (IsDark) Color(0xFF1D2D3A) else Color(0xFFEAF3FA)
    }
}
