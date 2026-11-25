package com.robomori.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.robomori.theme.RobomoriTheme

@Composable fun FormRow(title:String,content:@Composable ()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=30.dp),verticalAlignment=Alignment.CenterVertically) { Label(title,color=RobomoriTheme.colors.textSecondary);Spacer(Modifier.weight(1f));content() }
}
