# Mobile Trainer Link

Play **Mobile Trainer** (and other Mobile Adapter GB games) on a real Game Boy Color, with your
Android phone acting as the adapter's cell phone and internet connection.

```
Game Boy Color ──link cable──▶ Raspberry Pi Pico ──USB-C OTG──▶ Android phone ──▶ internet / REON server
                               (emulates the Mobile       (this app: sockets + DNS)
                                Adapter GB with libmobile)
```

The Pico runs [pico-gb-mobile-adapter](https://github.com/Lorenzooone/pico-gb-mobile-adapter)
by Lorenzooone, built on REON's [libmobile](https://github.com/REONTeam/libmobile). That firmware
does the timing-critical Mobile Adapter GB protocol with the Game Boy and hands every network
request (open, connect, send, receive) over USB to a host. Upstream the host is a Python script
on a PC; **this app is that host, on your phone.**

## Downloads

Every push to `main` builds both pieces and publishes them on the
[Releases page](https://github.com/fixmylifedesigns/mobile-trainer-link/releases/latest):

| File | Goes on | How |
|---|---|---|
| `MobileTrainerLink.apk` | Android phone | Open it on the phone and install. The app updates itself from new releases. |
| `MobileTrainerLink-Pico.uf2` | Raspberry Pi Pico (RP2040) | Hold **BOOTSEL**, plug the Pico into a computer, drag the file onto the `RPI-RP2` drive. |

## Wiring (Pico ↔ Game Boy link port)

Same pin layout as stacksmashing's USB-to-GB-link board:

| Pico | Link signal | Direction |
|---|---|---|
| GP0 | Game Boy **SO** (serial out) | GB → Pico |
| GP1 | SD (not used) | — |
| GP2 | **SCK** (clock, driven by the Game Boy) | GB → Pico |
| GP3 | Game Boy **SI** (serial in) | Pico → GB |
| GND | GND | — |

The Game Boy Color's link port runs at 5 V and the Pico's pins are 3.3 V only, so go through a
level shifter (the stacksmashing board has one built in). Don't connect the link cable's VCC to
the Pico. If you trace pins through a cable, remember SO and SI swap ends.

The phone powers the Pico over USB: phone USB-C → OTG adapter or cable → Pico.

## Using it

1. Flash the `.uf2` onto the Pico and install the `.apk`.
2. Plug the Pico into the phone. Android asks whether to open **Mobile Trainer Link**. Say yes
   (tick "always"), or open the app and tap **Allow USB**.
3. The status card turns green: **Connected to the Pico**. A notification keeps the link running
   with the screen off.
4. Turn on the Game Boy Color with Mobile Trainer.

### The server

Nintendo's Mobile System GB servers shut down in 2002. Mobile Trainer still looks up
`gameboy.datacenter.ne.jp`, `mgb.dion.ne.jp`, `pop.d6.dion.ne.jp` and so on, through the old
DION DNS servers baked into the cartridge. The app catches those DNS lookups:

- **Answer DNS on the phone** (default): every `*.dion.ne.jp` and `*.datacenter.ne.jp` name
  resolves to the **Game server IP** you enter, which is the machine running a
  [REON](https://github.com/REONTeam/reon) server (it has the web pages and the POP3/SMTP mail
  server Mobile Trainer uses). If that field is empty, those names return "not found" instead of
  reaching whoever owns those domains today. Other names, such as `www.pokemon.co.jp`, use the
  phone's normal DNS. You can turn that off.
- **Forward DNS**: sends the cartridge's DNS queries to a DNS server you run. REON's Docker
  setup includes one. The replies are relabelled so the cartridge accepts them.
- **Host overrides**: `host ip` lines, checked first. Start a host with `.` to match every
  subdomain.

To run REON yourself, follow the Docker steps in its README and use the server's LAN IP, or a VPS
IP if you want it to work away from home.

### Adapter model

**Adapter → Send to Pico** sets which Mobile Adapter GB the Pico pretends to be: Blue (PDC),
Yellow (cdmaOne) or Red (DDI). It's stored on the Pico. Power-cycle the Game Boy afterwards.

## How it's tested

`tools/host-test/` compiles the firmware's own `gbridge.c` / `socket_impl.c` natively as a
"fake Pico" and drives it with the app's Java bridge over a pipe. CI checks DNS answers, a TCP
echo round-trip, remote close (`-2`), connection refused, and the same run again with ~1 in 7
USB packets corrupted to exercise the checksum/retry path.

## Credits

- [Lorenzooone/pico-gb-mobile-adapter](https://github.com/Lorenzooone/pico-gb-mobile-adapter): Pico firmware and the
  GBridge USB protocol (CI builds it at commit `23dfb1b`).
- [REONTeam/libmobile](https://github.com/REONTeam/libmobile) (LGPL-3.0): Mobile Adapter GB emulation.
- [REONTeam/reon](https://github.com/REONTeam/reon): replacement Mobile System GB servers.

No game ROMs are included or downloaded by this project.
