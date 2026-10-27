# LightLauncher

[![Licencia: MIT](https://img.shields.io/badge/licencia-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/icarloscornejo/LightLauncher)](https://github.com/icarloscornejo/LightLauncher/releases/latest)

Una pantalla de inicio ligera, al estilo tvOS, para Android TV y Google TV. Un dock de vidrio, un estante
superior que muestra lo que tus apps quieren reproducir a continuación, un protector de pantalla con fotos y
unos ajustes que se manejan con el control remoto. Vistas normales de Android en Kotlin, sin analítica y sin
cuentas.

[Read in English](README.md)

![El inicio con las tarjetas Play Next de una app, la primera enfocada y agrandada](docs/images/cards-dark.jpg)

## Qué hace

- **Un dock y una grilla de apps.** Tus favoritas (seis por defecto, se cambian en `config.xml` o en un tema)
  sobre un panel de vidrio translúcido, donde una favorita que no está instalada deja su lugar vacío, y todas las
  demás apps abajo. El tile enfocado hace zoom y se levanta con una sombra.
- **Un estante superior** (apagado por defecto). La app enfocada del dock muestra lo que publica al TV:
  imágenes a pantalla completa que se funden despacio, o una fila de tarjetas "Play Next". La tarjeta enfocada
  crece y empuja a sus vecinas, como en el inicio de Netflix, y cada app recuerda en qué tarjeta ibas.

  ![Una app que publica arte a pantalla completa](docs/images/hero-dark.jpg)

- **Un fondo borroso.** Si bajás de una app del dock a la grilla, la imagen de su estante se queda detrás de las
  apps, borrosa y teñida según el tema.

  ![La grilla sobre la imagen borrosa a pantalla completa](docs/images/hero-grid-dark.jpg)
  ![La grilla sobre la imagen borrosa de la tarjeta seleccionada](docs/images/cards-grid-dark.jpg)

- **Claro y oscuro.** Fondos pálidos y suaves, o oscuros, a elección en los ajustes.

  ![La apariencia clara, con una imagen a pantalla completa](docs/images/hero-light.jpg)
  ![La apariencia clara, con las tarjetas](docs/images/cards-light.jpg)
  ![La apariencia clara, sobre la imagen borrosa de una tarjeta](docs/images/cards-grid-light.jpg)
  ![La apariencia clara, sobre la imagen borrosa a pantalla completa](docs/images/hero-grid-light.jpg)

- **Ordená las apps desde el TV.** Mantené presionada una app para moverla: Izquierda y Derecha cambian de lugar
  en el dock, Abajo cambia con la app de abajo, y en la grilla las demás se corren. Cada paso se guarda.

  ![El modo mover, con el tile de Netflix levantado](docs/images/move-mode.jpg)
- **Ajustes como la lista de tvOS.** Entradas (HDMI, AV, TV en vivo), Wi-Fi, ajustes del TV y la página propia del
  launcher.

  ![Los ajustes](docs/images/settings.jpg) ![Los ajustes del launcher](docs/images/launcher-settings.jpg)

- **Un protector de pantalla con fotos** de un álbum compartido de Google Fotos, o un reloj si no hay álbum.
- **Temas** que se empujan por ADB (`config.json` y un fondo opcional).
- Inglés y español, a elección en los ajustes o siguiendo al TV.

## Requisitos

Android TV o Google TV 8.0 (API 26) o más nuevo, con la depuración por red (ADB) activada en las opciones de
desarrollador. Probado en un Sony BRAVIA (Google TV, Android 12) y un Xiaomi TV box (Google TV, Android 14).

## Instalación

### Desde un release

Bajá `LightLauncher-<versión>.apk` de los [releases](https://github.com/icarloscornejo/LightLauncher/releases/latest)
y, con `<ip-del-tv>` la dirección que muestra la red del TV:

```sh
adb connect <ip-del-tv>:5555
adb install LightLauncher-1.0.0.apk
adb shell pm grant com.prototype.lightlauncher android.permission.READ_TV_LISTINGS
adb shell pm grant com.prototype.lightlauncher android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.prototype.lightlauncher android.permission.ACCESS_FINE_LOCATION
adb shell appops set com.prototype.lightlauncher REQUEST_INSTALL_PACKAGES allow
adb shell cmd package set-home-activity --user 0 com.prototype.lightlauncher/.MainActivity
```

Para qué sirve cada uno: `READ_TV_LISTINGS` deja que el estante lea lo que publican las apps;
`WRITE_SECURE_SETTINGS` deja que el launcher mantenga las animaciones a su velocidad normal en TVs que las
reinician y que cambie el DNS que bloquea anuncios; `ACCESS_FINE_LOCATION` es lo que Android pide antes de dejar
leer el nombre del Wi-Fi; los dos últimos permiten que el launcher instale sus propias actualizaciones y lo dejan
como app de inicio. Ninguno hace falta para usar el launcher, cada uno solo enciende una función.

En Google TV el botón Home puede seguir abriendo el launcher de Google. Desactivalo (podés volver a activarlo
cuando quieras):

```sh
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx     # o com.google.android.tvlauncher
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith   # el asistente de configuración, si también reclama el inicio
```

Desactivá el launcher viejo solo después de instalar este y dejarlo como inicio, o el botón Home no tendrá nada
que abrir.

### Desde el código

Requisitos: JDK 17, el Android SDK (plataforma 37) y Python 3 para los scripts de ayuda.

```sh
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
cd launcher
TV_HOST=<ip-del-tv> ./build-and-install.sh
```

El script compila el APK de release, lo instala, concede los permisos de arriba, lo deja como app de inicio y
apaga el launcher de fábrica (`STOCK_LAUNCHER=none` lo omite cuando ya está apagado). En Windows usá
`launcher\build-and-install.ps1 -TvHost <ip-del-tv>`. Pruebas unitarias: `./gradlew test`.

## Primer arranque

El estante superior empieza **apagado**: mientras está apagado el launcher no lee nada del TV ni descarga nada.
Se enciende en la píldora del reloj (arriba a la derecha), Ajustes del launcher, Contenido del estante superior.

Dos servicios de accesibilidad opcionales ayudan con controles de botones raros. Ambos están apagados hasta que
los encendás (pasalos de nuevo en cada instalación del script, o corré los comandos vos):

```sh
# HomeKeyService: controles cuyo botón Home manda "Todas las apps" (sticks Xiaomi). InputKeyService: el botón
# Input abre el selector de entradas. Usá uno, o los dos separados por dos puntos.
adb shell settings put secure enabled_accessibility_services com.prototype.lightlauncher/.HomeKeyService:com.prototype.lightlauncher/.InputKeyService
adb shell settings put secure accessibility_enabled 1
```

## Ajustes

Abrí la píldora del reloj (Arriba desde el dock, o Arriba otra vez desde las tarjetas).

| Fila | Qué hace |
|---|---|
| Entradas | HDMI, AV y TV en vivo, cuando el TV las lista |
| Ajustes del launcher | La página propia del launcher, abajo |
| Wi-Fi | El nombre de la red; OK abre la app de VPN si hay una, si no los ajustes de Wi-Fi; mantené OK para los ajustes de Wi-Fi |
| Ajustes del TV | Los ajustes del sistema |

| Ajustes del launcher | Qué hace |
|---|---|
| Protector de pantalla | Verlo ahora, elegir LightLauncher como protector y configurar el álbum de fotos |
| Idioma | Automático, inglés o español |
| Apariencia | Claro u Oscuro |
| Contenido del estante superior | Muestra arriba del dock lo que publica la app enfocada (apagado por defecto) |
| Bloquear anuncios (DNS) | Enciende el DNS privado de AdGuard, que bloquea anuncios y rastreadores en otras apps (no los de YouTube) |
| Buscar actualización | Busca un release nuevo en GitHub, ver abajo |
| Acerca de | La versión y dónde está el código |

## Protector de pantalla con fotos

Elegí LightLauncher como protector en Ajustes del launcher, Protector de pantalla. Para mostrar fotos, abrí
Álbum de fotos, Enlace del álbum: el TV muestra un código QR, lo escaneás con el celular y pegás el enlace de un
álbum compartido de Google Fotos. El TV sirve una página pequeña en tu red local solo durante ese momento (token
aleatorio, un enlace, cinco minutos como máximo).

También podés poner el enlace en un tema, ver [themes/README.md](themes/README.md):

```json
{ "dream_album_url": "https://photos.app.goo.gl/..." }
```

Cualquiera que tenga el enlace de un álbum compartido puede abrirlo, así que no subás el tuyo a un repo público.
Las fotos se bajan como máximo una vez al día, solo por https y solo de hosts de Google Fotos; las de 16:9 salen
solas y las más angostas de a dos, sin repetirse hasta mostrar todas.

## Actualizaciones

Ajustes del launcher, Buscar actualización busca un release más nuevo de este repositorio, baja su APK, lo
comprueba contra el SHA-256 que GitHub publica para él y se lo pasa al instalador de Android, que te pide
confirmar. Android solo instala una actualización sobre una app firmada con la misma llave, así que esto sirve
para los releases de este repositorio; si compilaste el launcher vos, compilá también la versión siguiente. Los
servicios de accesibilidad que estaban encendidos los vuelve a encender la versión nueva; si Android no se lo
permite, un mensaje lo avisa y los comandos de arriba los traen de vuelta.

## Privacidad

Sin analítica, sin cuentas, no se envía nada sobre vos. El launcher usa la red en tres lugares, todos opcionales o
a pedido, cada uno con reglas fijas escritas en [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) (en inglés):

- el álbum del protector de pantalla (https, solo hosts de Google Fotos),
- las imágenes del estante superior (https, solo para las apps del dock, con límites de tamaño y tiempo), y
- la búsqueda de actualizaciones (https, solo hosts de GitHub), cuando la pedís.

Bloquear anuncios (DNS) cambia el DNS privado del sistema al de AdGuard, así que las consultas DNS del TV pasan
entonces por AdGuard.

## Proyecto

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): arquitectura, reglas de red, pruebas en un TV, cómo publicar (en inglés).
- [themes/README.md](themes/README.md): las claves de `config.json` y cómo empujar un tema (en inglés).
- [CHANGELOG.md](CHANGELOG.md) y [SECURITY.md](SECURITY.md).

Las contribuciones son bienvenidas: respetá las capas descritas en las notas de desarrollo (`DependencyRulesTest`
las exige), agregá una prueba JVM junto a cualquier lógica nueva en `core` y corré `./gradlew test` antes de abrir
un pull request.

## Licencia

[MIT](LICENSE). Los créditos y avisos de terceros, incluida la tipografía Outfit, están en
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Netflix, Spotify, Apple TV, Prime Video, Max y las demás apps e imágenes de las capturas pertenecen a sus
dueños; aparecen solo porque están instaladas en el TV donde se tomaron las capturas.
