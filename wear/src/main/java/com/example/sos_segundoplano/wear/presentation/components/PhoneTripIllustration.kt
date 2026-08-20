package com.example.sos_segundoplano.wear.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.example.sos_segundoplano.wear.R

/** Uses the exact same rider/motorcycle artwork as the phone's "Listo para tu viaje" card. */
@Composable
internal fun PhoneTripIllustration(
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Image(
        painter = painterResource(R.drawable.img_home_rider),
        contentDescription = null,
        modifier = modifier.size(if (compact) 68.dp else 94.dp),
        contentScale = ContentScale.Fit
    )
}
