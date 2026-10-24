package com.mamba.picme.features.chat.openui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.mamba.picme.domain.chat.openui.OpenUiLibrary

/**
 * OpenUI Icon name → Material 图标映射（M2，spec《openui-component-library-m2-design》§4）。
 *
 * 名称域 = A2UI catalog v1 的 59 枚预定义图标名（禁 svgPath 同口径），与 shared
 * [OpenUiLibrary.iconNames] ENUM 域同源——[OpenUiIconMappingTest] 钉住每个名字都有映射。
 * 未知名返回 null（schema ENUM + server 白名单双层已校验，此处仅防御兜底，调用方丢组件不炸卡）。
 */
internal fun openUiIcon(name: String): ImageVector? = when (name) {
    "accountCircle" -> Icons.Filled.AccountCircle
    "add" -> Icons.Filled.Add
    "arrowBack" -> Icons.AutoMirrored.Filled.ArrowBack
    "arrowForward" -> Icons.AutoMirrored.Filled.ArrowForward
    "attachFile" -> Icons.Filled.AttachFile
    "calendarToday" -> Icons.Filled.CalendarToday
    "call" -> Icons.Filled.Call
    "camera" -> Icons.Filled.CameraAlt
    "check" -> Icons.Filled.Check
    "close" -> Icons.Filled.Close
    "delete" -> Icons.Filled.Delete
    "download" -> Icons.Filled.Download
    "edit" -> Icons.Filled.Edit
    "error" -> Icons.Filled.Error
    "event" -> Icons.Filled.Event
    "fastForward" -> Icons.Filled.FastForward
    "favorite" -> Icons.Filled.Favorite
    "favoriteOff" -> Icons.Filled.FavoriteBorder
    "folder" -> Icons.Filled.Folder
    "help" -> Icons.Filled.Help
    "home" -> Icons.Filled.Home
    "info" -> Icons.Filled.Info
    "locationOn" -> Icons.Filled.LocationOn
    "lock" -> Icons.Filled.Lock
    "lockOpen" -> Icons.Filled.LockOpen
    "mail" -> Icons.Filled.Mail
    "menu" -> Icons.Filled.Menu
    "moreHoriz" -> Icons.Filled.MoreHoriz
    "moreVert" -> Icons.Filled.MoreVert
    "notifications" -> Icons.Filled.Notifications
    "notificationsOff" -> Icons.Filled.NotificationsOff
    "pause" -> Icons.Filled.Pause
    "payment" -> Icons.Filled.Payment
    "person" -> Icons.Filled.Person
    "phone" -> Icons.Filled.Phone
    "photo" -> Icons.Filled.Photo
    "play" -> Icons.Filled.PlayArrow
    "print" -> Icons.Filled.Print
    "refresh" -> Icons.Filled.Refresh
    "rewind" -> Icons.Filled.FastRewind
    "search" -> Icons.Filled.Search
    "send" -> Icons.AutoMirrored.Filled.Send
    "settings" -> Icons.Filled.Settings
    "share" -> Icons.Filled.Share
    "shoppingCart" -> Icons.Filled.ShoppingCart
    "skipNext" -> Icons.Filled.SkipNext
    "skipPrevious" -> Icons.Filled.SkipPrevious
    "star" -> Icons.Filled.Star
    "starHalf" -> Icons.Filled.StarHalf
    "starOff" -> Icons.Filled.StarBorder
    "stop" -> Icons.Filled.Stop
    "upload" -> Icons.Filled.Upload
    "visibility" -> Icons.Filled.Visibility
    "visibilityOff" -> Icons.Filled.VisibilityOff
    "volumeDown" -> Icons.AutoMirrored.Filled.VolumeDown
    "volumeMute" -> Icons.AutoMirrored.Filled.VolumeMute
    "volumeOff" -> Icons.AutoMirrored.Filled.VolumeOff
    "volumeUp" -> Icons.AutoMirrored.Filled.VolumeUp
    "warning" -> Icons.Filled.Warning
    else -> null
}
