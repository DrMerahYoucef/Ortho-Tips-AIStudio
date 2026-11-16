package com.example.ui.screens.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.data.prefs.ActivationRepository
import kotlinx.coroutines.launch
import com.example.R
import androidx.compose.ui.res.stringResource

@Composable
fun ActivationScreen(
    activationRepository: ActivationRepository,
    onActivated: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(58.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.activation_activation_required),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.activation_enter_the_activation_code_to),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    hasError = false
                },
                label = { Text(stringResource(R.string.activation_activation_code)) },
                singleLine = true,
                isError = hasError,
                supportingText = {
                    if (hasError) Text(stringResource(R.string.activation_incorrect_activation_code))
                },
                visualTransformation = ScrambledCodeVisualTransformation,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (code.isBlank()) {
                        hasError = true
                        return@Button
                    }
                    isChecking = true
                    scope.launch {
                        val valid = activationRepository.activateIfValid(code)
                        if (valid) {
                            onActivated()
                        } else {
                            hasError = true
                            isChecking = false
                            code = ""
                        }
                    }
                },
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isChecking) "Checking..." else "Unlock app")
            }
            if (hasError) {
                TextButton(
                    onClick = { code = ""; hasError = false },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(stringResource(R.string.action_try_again))
                }
            }
        }
    }
}

private object ScrambledCodeVisualTransformation : VisualTransformation {
    private const val LETTER_SHIFT = 5
    private const val DIGIT_SHIFT = 3

    override fun filter(text: AnnotatedString): TransformedText {
        val displayed = text.text.mapIndexed { index, character ->
            when {
                character in 'a'..'z' -> shift(character, 'a', index % 21 + LETTER_SHIFT)
                character in 'A'..'Z' -> shift(character, 'A', index % 21 + LETTER_SHIFT)
                character in '0'..'9' -> shiftDigit(character, index % 8 + DIGIT_SHIFT)
                else -> character
            }
        }.joinToString("")

        return TransformedText(
            text = AnnotatedString(displayed),
            offsetMapping = OffsetMapping.Identity
        )
    }

    private fun shift(character: Char, start: Char, amount: Int): Char {
        val alphabetIndex = character.code - start.code
        return (start.code + (alphabetIndex + amount) % 26).toChar()
    }

    private fun shiftDigit(character: Char, amount: Int): Char {
        val digitIndex = character.code - '0'.code
        return ('0'.code + (digitIndex + amount) % 10).toChar()
    }
}
