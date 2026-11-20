# socket.io-client-java: Issues, PRs und Forks mit Jev geprüft

Stand: 25.09.2026. Bewertet wurde dieser Client auf `main` (Commit `c315d49` und
folgende). Quelle: alle 915 Issues und Pull Requests von
[socketio/socket.io-client-java](https://github.com/socketio/socket.io-client-java)
(792) und [socketio/engine.io-client-java](https://github.com/socketio/engine.io-client-java)
(123) sowie alle 1.185 Forks beider Repositories (Snapshot 25.09.2026).
Auswertung: read-only OpenCode-Agent `jev` (Gemini 3.8 Flash sammelt Evidenz,
TypeSafe Jev bewertet über die Evaluation-API).

Maßstab war der offizielle JavaScript-Client am gepinnten Commit
`aaf2af36`: Was dort nicht passiert, wird hier nicht eingebaut, auch wenn der
Java-Client oder ein Fork es anders macht.

Jev-Nutzung: Stufe 1 77 Aufrufe, 722.946 Eingabe-/110.385 Ausgabe-Tokens;
Stufe 2 64 Aufrufe, 584.011/43.332 Tokens. 59 Versuche endeten zunächst ohne
Urteil (52 davon mit HTTP 503 des Gateways) und wurden automatisch wiederholt;
kein Batch blieb ohne Urteil. Dazu kommen die Gemini-Läufe des Koordinators, die hier nicht abgerechnet
sind. Alle Wahrscheinlichkeiten sind Modellschätzungen, keine Beweise.

Rohdaten, Skripte und Ereignisprotokolle sind außerhalb des Repositories
archiviert und nicht veröffentlicht. Die Jev-Urteile sind als
[Stufe-1-CSV](ReviewEvidence/JavaUpstreamTriage-Stage1-2026-09-25.csv) und
[Stufe-2-CSV](ReviewEvidence/JavaUpstreamTriage-Stage2-2026-09-25.csv) beigelegt.

## Kernaussagen

1. **Ein echter Defekt betraf auch diesen Client und ist behoben.**
   [#773](https://github.com/socketio/socket.io-client-java/issues/773),
   [#726](https://github.com/socketio/socket.io-client-java/issues/726) und
   [engine#86](https://github.com/socketio/engine.io-client-java/issues/86):
   Viele große Emits hintereinander enden in `transport close`. Ursache ist OkHttp,
   das einen WebSocket schließt, sobald mehr als 16 MiB auf den Versand warten;
   Browser und Node kennen diese Grenze nicht. Unser WebSocket-Transport gab wie
   JavaScript jedes Paket sofort weiter und war betroffen (E2E-Test mit 40 × 1 MiB
   reproduziert den Abbruch). Der Transport übergibt OkHttp jetzt nur, was in die
   Queue passt, und meldet `drain` wie JavaScript erst nach dem letzten Paket
   (`WebSocketTransport`, `EngineWebSocketConnection.maxQueuedBytes`). Eine
   einzelne Nachricht über 16 MiB kann OkHttp grundsätzlich nicht senden; sie
   scheitert jetzt mit einem Transportfehler, der die Grenze nennt, und die
   Verbindung erholt sich. Tests: `WebSocketBackpressureTest`,
   `MaxPayloadE2ETest.aBurstOfLargeEmitsOverWebSocketArrivesWithoutClosingTheConnection`,
   `MaxPayloadE2ETest.aWebSocketMessageAboveOkHttpsLimitFailsWithAClearReasonAndTheSocketRecovers`.

2. **Der Großteil der Java-Historie lehrt nichts über diesen Client.** Stufe 1:
   358 Nutzungsfragen, 157 behobene oder veraltete Defekte (Socket.IO 1/2,
   OkHttp 3, Java 7), 60 Build- und Abhängigkeitsprobleme, 48 PRs ohne
   Verhaltensänderung, 28 Duplikate. Nur 350 Items gingen in Stufe 2.

3. **Stufe 2 (350 Kandidaten, davon 46 Fork-Änderungen) gegen Quellcode:**

   | Urteil | Items |
   | --- | ---: |
   | bereits adressiert | 191 |
   | betrifft nur die Java-Implementierung | 70 |
   | verhält sich wie der JS-Client | 40 |
   | würde vom JS-Client abweichen | 27 |
   | Evidenz reicht nicht | 13 |
   | betroffen, Fix innerhalb des JS-Verhaltens | 9 |

   Für 104 Items zitierte die Evidenz einen ausführbaren Kotlin-Test.

4. **Die neun „betroffen“-Urteile, von Hand geprüft:**
   - #773/#726-Klasse: siehe Punkt 1 (Jev sah den Fix bereits im Arbeitsstand und
     stufte engine#86 als adressiert ein).
   - [#333](https://github.com/socketio/socket.io-client-java/issues/333),
     [#334](https://github.com/socketio/socket.io-client-java/issues/334) (eigene
     Encoder/Decoder): zutreffend. JavaScript erlaubt eine eigene `parser`-Option;
     sie ist das einzige der 100 anwendbaren JS-API-Member, das hier fehlt
     ([API-Mapping](JavaScriptApiMapping.json)). **Offene Entscheidung.**
   - [#328](https://github.com/socketio/socket.io-client-java/issues/328) (Dauer-
     `xhr poll error` im schlechten Netz): verworfen. Hier greift der Backoff des
     JS-Clients; im Netz ohne Verbindung stellt der Android-Netzwerkmonitor die
     Versuche zurück.
   - [#295](https://github.com/socketio/socket.io-client-java/issues/295),
     [#313](https://github.com/socketio/socket.io-client-java/issues/313),
     [#346](https://github.com/socketio/socket.io-client-java/issues/346):
     verworfen. Exceptions aus OkHttp 3 (`sendMessage` warf bei geschlossenem
     Socket); OkHttp 5 liefert `false`, das der Transport behandelt.
   - [#788](https://github.com/socketio/socket.io-client-java/issues/788)
     (WebTransport): bewusst nicht unterstützt, siehe [PARITY.md](../PARITY.md).
   - Fork `zengbiaobiao` (Komma bei DISCONNECT eines Namespace weglassen):
     verworfen, weil JavaScript `1/nsp,` mit Komma sendet (Test JS-259).
   - [#785](https://github.com/socketio/socket.io-client-java/issues/785)
     (OpenSSF-Empfehlungen): übernommen für dieses Repository:
     [SECURITY.md](../SECURITY.md) und Dependabot; Workflows laufen bereits mit
     Leserechten und gepinnten Actions.

5. **Regressionstests für Java-Defekte, die dieser Client konstruktiv vermeidet:**
   [#743](https://github.com/socketio/socket.io-client-java/issues/743) (`int[]` in
   einem `JSONObject` kam als `"[I@50bf616"` an) –
   `OrgJsonConversionTest.primitiveArraysInsideOrgJsonBecomeJsonArrays`;
   [#567](https://github.com/socketio/socket.io-client-java/issues/567) (Ack-String,
   der wie JSON aussieht, wurde umkodiert) –
   `SocketApiTest.anIncomingAcknowledgementReportsWhetherItWasSent`;
   [#289](https://github.com/socketio/socket.io-client-java/issues/289) (nach vielen
   Connect/Disconnect-Zyklen kein `connect` mehr) –
   `ResilienceE2ETest.hundredsOfConnectDisconnectCyclesAllConnect` (150 Zyklen gegen
   den echten Server).

6. **Dokumentiert statt geändert:**
   [#324](https://github.com/socketio/socket.io-client-java/issues/324),
   [#104](https://github.com/socketio/socket.io-client-java/issues/104): Eine
   JVM-Anwendung beendet sich bis zu 60 s nach `close()` nicht, weil OkHttps
   Dispatcher Nicht-Daemon-Threads nutzt. Der Protokollteil läuft auf
   Daemon-Threads; der Weg über einen eigenen OkHttp-Client steht im
   [Configuration-Guide](Guides/Configuration.md). Das Gegenstück in Node ist
   `autoUnref`, das hier nicht anwendbar ist.

7. **Bewusst nicht übernommen (würde vom JS-Client abweichen):** unter anderem
   `rejectUnauthorized: false` (#366, #701; bewusste Entscheidung, stattdessen
   `TlsPolicy.customTrust`), ein manuell ausgelöstes Upgrade (Fork `N1k1tung`),
   eine Upgrade-Prüfung abweichend von JS (Fork `alibaba-archive`),
   Socket.IO-2-Kompatibilität (#439, engine#93), Streams (#65, #217) und eigene
   WebSocket-Pings (Fork `tankcong`). Einige Jev-Urteile in dieser Gruppe sind
   Fehletiketten ohne Folgen: #794 (Connection State Recovery, Auth) und #750
   (`sendBuffer`) sind in JavaScript und hier vorhanden; #755 (Android-Logging) ist
   mit `AndroidLogger` adressiert.

8. **Die 13 Items ohne ausreichende Evidenz** betreffen Server-seitiges Verhalten
   (#30, #737), Anwendungsfragen (#546, #713), Java-Threads (#376, #392) oder
   Feature-Ideen (#217, #254). Zwei wurden nachgeprüft: #470 (Arabisch ab
   v1.0.0) hat keine Beschreibung; Mehrbyte-UTF-8 ist hier durch JS-011 und die
   Byte-Limit-Tests mit Surrogaten abgedeckt. #244 (Reconnect nach Paketverlust
   verspätet) ging laut Diskussion auf OkHttps damals neuen Read-Timeout von 10 s
   zurück, der Long-Polls abbrach; hier ist der Read-Timeout abgeschaltet
   (`OkHttpEngineClientsTest.hasNoReadTimeoutSoLongPollsCanWait`), und der
   Heartbeat erkennt tote Verbindungen wie in JavaScript.

## Entscheidungen

1. **Backoff nach sehr vielen Versuchen.** Bei der Nachprüfung von
   [#107](https://github.com/socketio/socket.io-client-java/issues/107)
   (`delay < 0`) zeigte sich eine Eigenheit des JavaScript-Originals, die dieser
   Port exakt übernimmt: Nach rund 1.024 aufeinanderfolgenden Fehlversuchen wird
   `min × 2^attempts` unendlich, mit Jitter entsteht in etwa der Hälfte der Fälle
   `NaN`, und `Math.min(NaN, max) | 0` ergibt 0 ms. Mit
   `reconnectionDelayMax = 5 s` passiert das nach etwa 85 Minuten
   ununterbrochener Fehlversuche; danach folgen viele Versuche ohne Pause. In
   JavaScript und in Kotlin nachgestellt (gleiche Folge von 0- und 5000-ms-Werten).
   **Entschieden am 25.09.2026:** bewusste Abweichung. Die Wartezeit bleibt dort
   bei `reconnectionDelayMax` (`Backoff.duration`,
   `SocketManagerTest.theBackoffStaysAtTheMaximumAfterThousandsOfAttempts`); alle
   früheren Werte sind unverändert. Dokumentiert in [PARITY.md](../PARITY.md) und
   im [Compatibility-Guide](Guides/Compatibility.md). Der iOS-Client verhält sich
   bereits so (`reconnectInterval` begrenzt den Exponenten auf 60 und fällt bei
   nicht endlichen Werten auf das Maximum zurück).
2. **Eigener Parser (#333, #334).** Innerhalb des JS-Standards möglich.
   **Entschieden am 25.09.2026:** nicht umgesetzt, weil der iOS-Client
   (kaeferfreund/socket.io-client-swift) ebenfalls keinen austauschbaren Parser
   anbietet (dort offen als Upstream-Issue #1150); beide Clients bleiben gleich.

## Methode und Grenzen

Stufe 1 ordnete jedes Item nach Titel, Text, Labels, Maintainer-Kommentaren und
bei PRs den geänderten Dateien einer von zehn Klassen zu (12 Items pro Aufruf;
mit 24 lieferte das Gateway wiederholt HTTP 503). Kandidaten für Stufe 2 waren aktuelle Defekte,
Feature-Wünsche, Doku-Lücken, funktionale PRs und unsichere Fälle, dazu jede
Fork-Änderung an `src/main` (identische Diffs zusammengefasst: 46 Gruppen aus 75
Forks mit eigenen Commits). In Stufe 2 las der Agent je Thema die passenden
Kotlin-Dateien, suchte nach den Symbolen des Items, zitierte Code und das
Verhalten des JS-Originals und übergab Item und Zitate unverändert an Jev
(sechs Items pro Aufruf).

Grenzen: Jev bewertet nur die zitierte Evidenz; ein „bereits adressiert“ ist
keine Testabdeckung, und nicht jedes Urteil wurde von Hand nachgeprüft. Geprüft
wurden alle „betroffen“-Urteile, die Urteile mit hoher Priorität und die Items,
aus denen Tests oder Änderungen entstanden sind. 50 Forks ließen sich nicht mit
dem Upstream vergleichen (gelöschte Branches oder unverbundene Historie).
