package com.mamba.picme.features.gallery.swipe

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import java.text.DateFormatSymbols

/** 月份标签（五语经 swipe_month_title 模板；月名取应用语言环境的 DateFormatSymbols）。 */
@Composable
fun swipeMonthLabel(year: Int, month: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    val monthName = DateFormatSymbols.getInstance(locale).months[month - 1]
    return stringResource(R.string.swipe_month_title, monthName, year)
}
