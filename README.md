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

## Hardware: GB-Link adapter or a DIY Pico board

The firmware uses the same pins as **GB-Link** adapters (and the agtbaskara / weimanc Pico
link boards they're based on), so a GB-Link board works as-is:

| Pico | Link signal | Direction |
|---|---|---|
| GP0 | **SCK** (clock, driven by the Game Boy) | GB → Pico |
| GP1 | Game Boy **SO** (serial out) | GB → Pico |
| GP2 | Game Boy **SI** (serial in) | Pico → GB |
| GP11 / GP12 | GB-Link voltage switch (3.3 V / 5 V, active low) | set to **5 V** at power-on |
| GND | GND | — |

The Game Boy Color's link port runs at 5 V and the Pico's pins are 3.3 V only, so a DIY build
needs a level shifter. On GB-Link boards the firmware selects 5 V on its own, the same way
GB-Link's Game Boy tools do (3.3 V is only for GBA). Don't connect the link cable's VCC to the
Pico. The phone powers the Pico over USB: phone USB-C → OTG adapter or cable → Pico.

### Coming from GB-Link firmware

The Mobile Adapter needs its own firmware: the Game Boy expects the adapter's reply between one
byte and the next, faster than a round trip over USB, so that logic has to run on the Pico. Flashing this UF2 replaces the GB-Link
firmware, so the GB-Link web tools (multiboot, Pokémon trading, printer…) stop working until you
switch back. To switch back, reflash GB-Link's firmware from
[launcher.gblink.io](https://launcher.gblink.io), or drag `gblink.uf2` from
[GBLink-Firmware releases](https://github.com/GB-Link/GBLink-Firmware/releases) onto the
`RPI-RP2` drive. Nothing on the Pico is permanent.

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
