package com.mamba.picme.features.chat.media

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * 聊天卡片媒体播放器（A2UI Video/AudioPlayer 渲染器与 OpenUI 未来媒体组件的共用件，
 * 中性包不绑定任一线格式；尺寸 token 由各线调用点传入）。
 *
 * 行为约定：卡片语境不自动播放（playWhenReady=false，用户点控制器才播），
 * 离开组合即释放播放器；播放/加载错误经 [onError] 上抛给线格式渲染器。
 */
@Composable
fun ChatVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    onError: (Throwable) -> Unit = {},
) {
    val player = rememberCardPlayer(url, onError)
    CardPlayerView(player, modifier, contentDescription = null)
}

@Composable
fun ChatAudioPlayer(
    url: String,
    description: String?,
    modifier: Modifier = Modifier,
    onError: (Throwable) -> Unit = {},
) {
    val player = rememberCardPlayer(url, onError)
    CardPlayerView(player, modifier, contentDescription = description?.ifBlank { null })
}

@Composable
private fun rememberCardPlayer(url: String, onError: (Throwable) -> Unit): ExoPlayer {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player, onError) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    return player
}

@OptIn(UnstableApi::class)
@Composable
private fun CardPlayerView(player: ExoPlayer, modifier: Modifier, contentDescription: CharSequence?) {
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
                this.contentDescription = contentDescription
            }
        },
        modifier = modifier,
    )
}
