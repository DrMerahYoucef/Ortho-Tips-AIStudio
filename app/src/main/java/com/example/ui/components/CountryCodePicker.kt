package com.example.ui.components

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import java.text.Normalizer
import java.util.Locale

/** A country and its international dialling code (without the "+"). */
data class CountryDialCode(val iso: String, val dialCode: String) {
    val flag: String
        get() = iso.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

    fun displayName(locale: Locale): String = Locale("", iso).getDisplayCountry(locale).ifBlank { iso }
}

object CountryDialCodes {
    private const val RAW =
        "AF:93 AL:355 DZ:213 AS:1 AD:376 AO:244 AI:1 AG:1 AR:54 AM:374 AW:297 AU:61 AT:43 AZ:994 " +
        "BS:1 BH:973 BD:880 BB:1 BY:375 BE:32 BZ:501 BJ:229 BM:1 BT:975 BO:591 BA:387 BW:267 BR:55 " +
        "VG:1 BN:673 BG:359 BF:226 BI:257 KH:855 CM:237 CA:1 CV:238 KY:1 CF:236 TD:235 CL:56 CN:86 " +
        "CO:57 KM:269 CG:242 CD:243 CK:682 CR:506 CI:225 HR:385 CU:53 CW:599 CY:357 CZ:420 DK:45 " +
        "DJ:253 DM:1 DO:1 EC:593 EG:20 SV:503 GQ:240 ER:291 EE:372 SZ:268 ET:251 FK:500 FO:298 FJ:679 " +
        "FI:358 FR:33 GF:594 PF:689 GA:241 GM:220 GE:995 DE:49 GH:233 GI:350 GR:30 GL:299 GD:1 GP:590 " +
        "GU:1 GT:502 GN:224 GW:245 GY:592 HT:509 HN:504 HK:852 HU:36 IS:354 IN:91 ID:62 IR:98 IQ:964 " +
        "IE:353 IL:972 IT:39 JM:1 JP:81 JO:962 KZ:7 KE:254 KI:686 XK:383 KW:965 KG:996 LA:856 LV:371 " +
        "LB:961 LS:266 LR:231 LY:218 LI:423 LT:370 LU:352 MO:853 MG:261 MW:265 MY:60 MV:960 ML:223 " +
        "MT:356 MH:692 MQ:596 MR:222 MU:230 YT:262 MX:52 FM:691 MD:373 MC:377 MN:976 ME:382 MS:1 " +
        "MA:212 MZ:258 MM:95 NA:264 NR:674 NP:977 NL:31 NC:687 NZ:64 NI:505 NE:227 NG:234 NU:683 " +
        "KP:850 MK:389 MP:1 NO:47 OM:968 PK:92 PW:680 PS:970 PA:507 PG:675 PY:595 PE:51 PH:63 PL:48 " +
        "PT:351 PR:1 QA:974 RE:262 RO:40 RU:7 RW:250 KN:1 LC:1 PM:508 VC:1 WS:685 SM:378 ST:239 " +
        "SA:966 SN:221 RS:381 SC:248 SL:232 SG:65 SX:1 SK:421 SI:386 SB:677 SO:252 ZA:27 KR:82 SS:211 " +
        "ES:34 LK:94 SD:249 SR:597 SE:46 CH:41 SY:963 TW:886 TJ:992 TZ:255 TH:66 TL:670 TG:228 TO:676 " +
        "TT:1 TN:216 TR:90 TM:993 TC:1 TV:688 UG:256 UA:380 AE:971 GB:44 US:1 UY:598 VI:1 UZ:998 " +
        "VU:678 VA:39 VE:58 VN:84 WF:681 YE:967 ZM:260 ZW:263"

    val all: List<CountryDialCode> = RAW.split(' ').map {
        val (iso, code) = it.split(':')
        CountryDialCode(iso, code)
    }

    fun byIso(iso: String?): CountryDialCode? = all.firstOrNull { it.iso.equals(iso, ignoreCase = true) }

    /** Best guess for the user's country: SIM, then network, then the phone's region. Falls back to Algeria. */
    fun guess(context: Context): CountryDialCode {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return byIso(tm?.simCountryIso?.takeIf { it.isNotBlank() })
            ?: byIso(tm?.networkCountryIso?.takeIf { it.isNotBlank() })
            ?: byIso(Locale.getDefault().country)
            ?: byIso("DZ")!!
    }

    /** Splits "+213555…" into a country and the local part, preferring [preferred] when codes are shared (+1, +7). */
    fun split(full: String, preferred: CountryDialCode): Pair<CountryDialCode, String>? {
        val digits = full.filter { it.isDigit() }
        if (!full.trim().startsWith("+") || digits.isEmpty()) return null
        if (digits.startsWith(preferred.dialCode)) return preferred to digits.removePrefix(preferred.dialCode)
        val match = all.filter { digits.startsWith(it.dialCode) }.maxByOrNull { it.dialCode.length } ?: return null
        return match to digits.removePrefix(match.dialCode)
    }
}

private fun String.foldAccents(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/**
 * Phone number input with a country dropdown (flag + "+213") and a separate field for the local
 * number. [onFullNumberChange] always receives the international form, e.g. "+213555123456";
 * a leading trunk "0" typed in the local part is dropped, as Telegram expects.
 */
@Composable
fun InternationalPhoneField(
    fullNumber: String,
    onFullNumberChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val guessed = remember { CountryDialCodes.guess(context) }
    val initial = remember { CountryDialCodes.split(fullNumber, guessed) }

    var iso by rememberSaveable { mutableStateOf(initial?.first?.iso ?: guessed.iso) }
    var local by rememberSaveable { mutableStateOf(initial?.second ?: "") }
    var showPicker by remember { mutableStateOf(false) }
    val country = CountryDialCodes.byIso(iso) ?: guessed

    fun emit(c: CountryDialCode, localPart: String) {
        // Drop the national trunk "0" (0555… → 555…). Italy keeps it: it is part of the number there.
        val raw = localPart.filter { it.isDigit() }
        val digits = if (c.dialCode == "39") raw else raw.trimStart('0')
        onFullNumberChange(if (digits.isEmpty()) "" else "+${c.dialCode}$digits")
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(130.dp)) {
            OutlinedTextField(
                value = "${country.flag} +${country.dialCode}",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.telegram_setup_wizard_country)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            // A read-only text field swallows taps, so an invisible layer on top opens the picker.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(enabled = enabled) { showPicker = true }
            )
        }
        OutlinedTextField(
            value = local,
            onValueChange = { v ->
                val cleaned = v.filter { it.isDigit() || it == ' ' }.take(20)
                local = cleaned
                emit(country, cleaned)
            },
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.telegram_setup_wizard_phone_number)) },
            placeholder = { Text(stringResource(R.string.telegram_setup_wizard_phone_number_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.weight(1f)
        )
    }

    if (showPicker) {
        CountryPickerDialog(
            locale = locale,
            onDismiss = { showPicker = false },
            onSelected = { picked ->
                iso = picked.iso
                showPicker = false
                emit(picked, local)
            }
        )
    }
}

@Composable
private fun CountryPickerDialog(
    locale: Locale,
    onDismiss: () -> Unit,
    onSelected: (CountryDialCode) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val sorted = remember(locale) {
        CountryDialCodes.all
            .map { it to it.displayName(locale) }
            .sortedBy { it.second.foldAccents() }
    }
    val q = query.trim().removePrefix("+").foldAccents()
    val filtered = if (q.isEmpty()) sorted else sorted.filter { (c, name) ->
        name.foldAccents().contains(q) || c.dialCode.startsWith(q) || c.iso.equals(q, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.telegram_setup_wizard_choose_country)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.telegram_setup_wizard_search_country)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.telegram_setup_wizard_no_country_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(filtered, key = { it.first.iso }) { (c, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelected(c) }
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            Text(c.flag, modifier = Modifier.width(36.dp))
                            Text(name, modifier = Modifier.weight(1f))
                            Text(
                                "+${c.dialCode}",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
