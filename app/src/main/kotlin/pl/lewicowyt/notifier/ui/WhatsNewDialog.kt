package pl.lewicowyt.notifier.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.i18n.tr

/** jedna duża zmiana: krótki opis na górze okna + szczegóły (podpunkty) niżej. */
private data class WhatsNewSection(
    val title: String,
    val summary: String,
    val details: List<String>,
)

/** zmiany 1.9-beta względem 1.8-beta — tylko to, co realnie jest w aplikacji. */
private val WHATS_NEW_1_9 = listOf(
    WhatsNewSection(
        title = "Powiadomienia przez serwer",
        summary = "Nowe filmy zgłasza serwer, a nie ciągłe sprawdzanie YouTube w tle — szybciej i oszczędniej dla baterii.",
        details = listOf(
            "Serwer domyślny lewicowYT sam sprawdza publiczne kanały RSS twórców i wysyła budzik; telefon nie odpytuje już YouTube co kilkanaście minut.",
            "Głównym torem jest UnifiedPush bez Google Play i bez FCM: wbudowany odbiornik (bez osobnej aplikacji) albo zewnętrzny dystrybutor, np. ntfy. Serwer domyślny w ogóle nie używa push Google (FCM); FCM zostaje tylko jako awaryjna opcja dla serwerów społeczności, które go oferują, i jest domyślnie wyłączony.",
            "To telefon decyduje, co pokazać: filtruje po wybranych twórcach i rodzajach materiałów, a miniatury i opisy pobiera lokalnie. Serwer nie zna Twoich ustawień.",
            "Aplikacja nie potrzebuje już dostępu „Alarmy i przypomnienia” ani innych uprawnień od pracy w tle, które były w 1.8.",
            "Podpowiedź, jak ustawić baterię „Bez ograniczeń”, gdy system opóźnia powiadomienia (otwiera ustawienia Androida, bez dodatkowych uprawnień).",
            "Gdy powiadomienie wyprzedzi RSS YouTube, prawdziwy tytuł zastępuje „Nowy materiał” automatycznie w ciągu kilku minut do kilku godzin.",
        ),
    ),
    WhatsNewSection(
        title = "Godziny i zegarek",
        summary = "Wybierasz godziny powiadomień, a powiadomienia mogą trafiać też na zegarek.",
        details = listOf(
            "„Godziny powiadomień” w Ustawieniach: poza wybranym oknem powiadomienia czekają, a po jego otwarciu przychodzą jako jedno zbiorcze.",
            "Powiadomienia o nowych filmach i wpisach społeczności pojawiają się na sparowanym zegarku z akcją „Otwórz na telefonie” — bez bibliotek Google.",
            "To, czy zegarek je pokaże, ustawiasz w aplikacji zegarka (np. Mi Fitness, Zepp, Wear OS); komunikaty techniczne zostają tylko na telefonie.",
        ),
    ),
    WhatsNewSection(
        title = "Serwery społeczności",
        summary = "Nowa zakładka „Społeczność” z treściami z serwerów, którym ufasz: posty, artykuły, memy i więcej.",
        details = listOf(
            "Dodajesz adres serwera (wystarczy skrócony — domyślny port to 2137) i akceptujesz jego regulamin; bez zgody aplikacja się z nim nie łączy.",
            "Rodzaje treści: dodatkowi twórcy, wpisy z innych platform, posty społeczności YouTube, artykuły i newslettery, memy oraz konkursy.",
            "Reklamy — jeśli serwer je ma — są widoczne tylko w zakładce, nigdy jako powiadomienie.",
            "Filtry po rodzaju treści i po serwerze; ta sama treść z kilku serwerów pokazuje się raz.",
            "Ulubione (gwiazdka): ich obrazy nie są kasowane przy sprzątaniu pamięci.",
            "Powiadomienia i wyświetlanie ustawiasz osobno — dla serwera, platformy, twórcy i rodzaju treści.",
            "Serwer główny: możesz wskazać serwer społeczności zamiast domyślnego. Aplikacja co jakiś czas porównuje go z RSS YouTube i ostrzega, gdy coś się nie zgadza.",
            "Platformy linków: wybierasz, czym otwierać linki np. do Twittera czy Twitcha (domyślnie, pytaj, przeglądarka lub wybrana aplikacja).",
            "„Przeładuj bazę z serwerów” pobiera wszystko od nowa i sprawdza zmiany sumą SHA-256.",
            "Serwery we własnej sieci domowej (http tylko w sieci lokalnej) i płynne przełączanie między Wi‑Fi a danymi komórkowymi.",
            "Ustawienia serwerów są w osobnej podsekcji — zębatka w prawym górnym rogu „Serwerów społeczności”.",
        ),
    ),
    WhatsNewSection(
        title = "Prywatność i Tor",
        summary = "Wbudowany Tor dla serwerów .onion, szyfrowane powiadomienia i żadnych Twoich danych na serwerach.",
        details = listOf(
            "Wbudowany Tor: serwery .onion działają bez instalowania Orbota.",
            "Serwer dostaje tylko ulotny adres dostarczenia i klucz publiczny — i to wyłącznie, gdy masz dla niego włączone powiadomienia. Treść budzika jest szyfrowana end-to-end, a adres zmienia się po każdym powiadomieniu.",
            "„Zmień tożsamość Tor” jednym przyciskiem: nowe obwody Tora i nowa rejestracja na wszystkich serwerach.",
            "Obrazy, dźwięk i wideo są pobierane wyłącznie z hosta serwera; aplikacja nigdy nie wykonuje kodu z serwera.",
            "Zapytania do YouTube, Google i GitHuba wyglądają jak zwykła przeglądarka.",
        ),
    ),
    WhatsNewSection(
        title = "Memy, dźwięk i wideo",
        summary = "Memy z animacjami, natywny dźwięk Opus i krótkie filmy odtwarzane w aplikacji.",
        details = listOf(
            "Memy w karuzeli, z animowanymi WebP; przytrzymanie obrazu zapisuje go w Galerii.",
            "Dźwięk Opus odtwarzany w aplikacji, także w tle — z pętlą, prędkością do 3× i sterowaniem z powiadomienia oraz ekranu blokady.",
            "Wideo H.265 lub H.264 do 30 kl/s, z tym samym powiadomieniem odtwarzacza; na danych komórkowych aplikacja pyta przed pobraniem.",
            "Format pliku jest sprawdzany w trakcie pobierania — niepasujący plik zostaje przerwany po pierwszych kilobajtach, bez marnowania transferu.",
            "Obrazy zapisywane lokalnie w JPEG XL (wyświetlanym progresywnie), z limitem rozmiaru i masową konwersją już zapisanych zdjęć.",
            "Osobno ustawiasz, jak długo trzymać tekst, a jak długo obrazy.",
        ),
    ),
    WhatsNewSection(
        title = "Historia",
        summary = "Historia i powiadomienia w jednej zakładce, odświeżanie gestem i oznaczanie prywatnych transmisji.",
        details = listOf(
            "Zakładki „Historia” i „Powiadomienia” połączone: przełącznik „Wszystkie / 🔔 Powiadomienia” z licznikiem nowych od ostatniej wizyty.",
            "Przeciągnij w dół, żeby odświeżyć — osobno w „Historii” i „Społeczności”; samo wejście na zakładkę już jej nie przeładowuje.",
            "Transmisje, które po czasie stały się prywatne, są oznaczane 🔒, nie pobierają transkrypcji i nie zaśmiecają wyszukiwania.",
            "Kanały bez kart Filmy/Shorty/Transmisje nie przerywają już pobierania starszej historii błędem.",
            "Shorty nie dostają już wymyślonej daty publikacji, gdy ich tytuł zaczyna się np. od „Dzisiaj”.",
        ),
    ),
    WhatsNewSection(
        title = "Wygoda, język i dostępność",
        summary = "Własne tłumaczenia, wybór zaimków, lepszy TalkBack i prostsze otwieranie linków.",
        details = listOf(
            "Możesz wgrać własne tłumaczenie aplikacji (i wyeksportować szablon) — bez aktualizacji APK.",
            "Wybór zaimków (on/ona/ono): aplikacja dobiera formy rodzajowe w tekstach.",
            "TalkBack: zakładki z pełnym opisem nowych powiadomień, opisane zdjęcia w karuzeli memów i akcje „Następne/Poprzednie zdjęcie”.",
            "Otwieranie linków YouTube: opcja „YouTube” używa domyślnej aplikacji systemu, a zamiast stałych pozycji dla innych klientów możesz wskazać dowolną własną aplikację. Wybór z okna wyboru jest zapamiętywany.",
            "Nowy twórca w katalogu: vrzenie.",
        ),
    ),
    WhatsNewSection(
        title = "Pod maską",
        summary = "Zgodność z nowymi Androidami, mniejszy APK i dziesiątki poprawek stabilności oraz bezpieczeństwa.",
        details = listOf(
            "Zgodność ze stronami pamięci 16 KB (Android 15 i nowsze).",
            "Mniejszy APK: AVIF tylko do odczytu (zapis zastępuje JPEG XL). Dodatkowo wariant „universal” — jeden plik na wszystkie procesory.",
            "Kilka pełnych audytów kodu i naprawione wszystkie ich ustalenia: odporność na awarie przy starcie, pełną pamięć i zrywane połączenia, szczelniejsze dzienniki i walidacja adresów.",
        ),
    ),
)

/** „Co nowego": na górze krótkie punkty (dotknięcie przewija do szczegółów), niżej podpunkty z konkretami. */
@Composable
internal fun WhatsNewDialog(
    onDismiss: () -> Unit,
    onOpenProjectPage: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // pozycja nagłówka każdej sekcji w przewijanej kolumnie — cel dla punktów z góry
    val sectionOffsets = remember { mutableStateMapOf<Int, Int>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Co nowego w lewicowYT 1.9-beta")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    tr("Najważniejsze zmiany względem 1.8-beta"),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                WHATS_NEW_1_9.forEachIndexed { index, section ->
                    Row(
                        modifier = Modifier.clickable(role = Role.Button) {
                            sectionOffsets[index]?.let { y -> scope.launch { scrollState.animateScrollTo(y) } }
                        },
                    ) {
                        Text("•  ")
                        Text(
                            buildString {
                                append(tr(section.title))
                                append(" — ")
                                append(tr(section.summary))
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                WHATS_NEW_1_9.forEachIndexed { index, section ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        tr(section.title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .semantics { heading() }
                            .onGloballyPositioned { sectionOffsets[index] = it.positionInParent().y.toInt() },
                    )
                    section.details.forEach { detail ->
                        Row(modifier = Modifier.padding(start = 8.dp)) {
                            Text("–  ")
                            Text(tr(detail), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onOpenProjectPage) {
                    Text(tr("Pełna lista zmian na stronie projektu"))
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(tr("Rozumiem"))
            }
        },
    )
}
