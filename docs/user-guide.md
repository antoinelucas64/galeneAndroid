# Galène Client — user guide

*Version française : [guide-utilisateur.md](guide-utilisateur.md)*

Galène Client lets you take part, from an Android phone, in the video meetings of a
[Galène](https://galene.org) server: video, audio, chat, raised hands, as with the web
client, but with proper background operation.

- [Installing](#installing)
- [Joining a meeting](#joining-a-meeting)
- [During the meeting](#during-the-meeting)
- [Chat](#chat)
- [Participants](#participants)
- [For operators](#for-operators)
- [Leaving the app without leaving the call](#leaving-the-app-without-leaving-the-call)
- [Network and mobile data](#network-and-mobile-data)
- [Language](#language)
- [Privacy](#privacy)
- [Troubleshooting](#troubleshooting)

## Installing

Android 8.0 or later is required.

- **F-Droid**: publication is in progress; the app will then be updated automatically.
- **APK file**: download the APK and open it. The first time, Android asks you to allow
  installing apps from your browser or file manager. If you have the choice, take
  `arm64-v8a` (almost every recent phone); the `universal` APK works everywhere but is
  three times larger.

The app needs no account and no Google services: it runs on LineageOS, /e/OS,
GrapheneOS…

## Joining a meeting

### With an invitation link

The simplest way. Copy the link you received, for example:

```
https://galene.example.org/group/meeting/?token=ABCDEF
```

paste it into the **Server or invitation link** field, then tap **Continue**.
The user name becomes optional; it is only asked for if the link requires it.

### With the server address

1. Type the server address (`galene.example.org`), then **Continue**.
2. The app lists the server's **public groups**, with the number of participants.
   An unlisted group is joined by typing its name in **Other group (name)**.
   A **Locked** group only lets its operators in.
3. Fill in the group's form:
   - **Username** and **Password**, if the group asks for them. For a group tied to an
     authentication server (YunoHost accounts, for instance), these are your usual
     credentials: that server checks them.
   - **On entering, publish**: **Nothing** (listen only), **Microphone**, or
     **Camera + microphone**.
4. **Join**. Android then asks for microphone and camera access, depending on your
   choice, and for permission to show a notification (Android 13 and later).

The last server and user name are remembered. Passwords never are.

If the server refuses you (wrong password, group full…), you are taken back to the form
with the reason.

> Some groups authenticate through a web portal: the app does not support that yet.
> Ask the organiser for an invitation link.

## During the meeting

### The screen

Each participant has a tile; tiles without video show the name's initial. Your own
picture is the **Me** tile.

- **Tap a tile** to see it fullscreen; tap again, or press back, to return to the grid.
- **Coloured outline** around a tile: that person is speaking.
- **✋ on a tile**: that person has raised their hand.

### The buttons

At the bottom, from left to right:

| Button | What it does |
|---|---|
| 🎤 Microphone | Mute / unmute your microphone |
| 📷 Camera | Turn your camera off / on (it really turns off, light included) |
| ✋ Hand | Raise / lower your hand; the button stays highlighted while it is up |
| 💬 Chat | Open the messages; a badge counts unread ones |
| 📞 red | Leave the meeting |

At the top:

| Button | What it does |
|---|---|
| ← | Leave the meeting |
| 🔄 Switch camera | Front / back camera |
| 🔊 Audio output | Choose where the sound goes (see below) |
| 👥 Participants | The participant list; a badge counts raised hands |

The microphone and camera buttons are greyed out when you do not publish that medium
(**Nothing** or **Microphone** chosen on entering, or permission denied).

### Raising your hand

Tap ✋. Everyone sees it, including people on the web client (an icon next to your name
in their user list). When someone else raises their hand, "… raised their hand" shows
at the bottom of the screen.

In the web client, one raises a hand by typing `/raise` in the chat (`/unraise` to
lower it).

### Sound: loudspeaker, earpiece, headset, Bluetooth

Sound goes automatically to the best device available: Bluetooth headset, otherwise
wired or USB headset, otherwise loudspeaker. Plugging in or connecting a headset during
the meeting switches the sound to it, microphone included.

The **Audio output** button lets you choose: **Loudspeaker**, **Phone earpiece** (phone
held to the ear), **Headset** or **Bluetooth**.

### Incoming phone call

A phone call interrupts the meeting's sound; it resumes as soon as you hang up.

## Chat

Tap 💬. Messages show as bubbles, yours on the right. Links can be tapped. When you
join a group, you see the messages already exchanged.

While the chat is closed, a new message appears for a few seconds above the videos; tap
it to open the chat.

- **Private message**: above the input field, **To: Everyone ▾** lets you pick a
  participant. Only they see the message. To answer a private message you received, tap
  it: its author becomes the recipient.
- **Action**: `/me waves` shows as "* Antoine waves".
- **Messages from operators and the server** (warnings, information) are shown centred.

## Participants

Tap 👥 at the top. The list shows raised hands first, then you, then the others by name.
The initial lights up while the person speaks; operators are marked.

A participant's **⋮** menu offers **Private message**, which opens the chat already
addressed to them.

## For operators

If you are an operator of the group, each participant's **⋮** menu also offers the web
client's actions:

- **Mute microphone**: their microphone is muted (they may turn it back on);
- **Allow to present** / **Forbid presenting**: the right to send audio and video;
- **Make operator** / **Remove operator rights**;
- **Remove from the group**, after confirmation.

Conversely, an operator may mute your microphone (a message tells you), clear the chat,
or remove you from the meeting.

## Leaving the app without leaving the call

- **Home button or home gesture**: the meeting shrinks into a **small floating window**
  (Picture-in-Picture) showing whoever is speaking. It has two buttons: microphone and
  hang up. Tap the window to go back fullscreen, drag it to move it.
- **Window closed, or another app in front**: the meeting goes on in the background,
  with sound. Your camera turns off so that it never films you unawares, and turns back
  on when you return. A **Call in progress** notification brings you back at any time.
- The screen does not go to sleep during the meeting.

To really leave: the red button, the ← arrow, or hang up in the small window.

## Network and mobile data

- **Network change** (Wi-Fi → 4G when leaving home, short outage): the app reconnects
  by itself and shows **Reconnecting…**. Microphone and camera stay ready; the others'
  picture and sound come back after a few seconds. After two minutes without network,
  it gives up and tells you.
- **Mobile data**: the app automatically asks for lighter video from the other
  participants, to save your data plan, and goes back to normal quality on Wi-Fi. (This
  works when the sender publishes two qualities, as the web client does.)

## Language

The app follows the phone's language: English, French, German, Spanish, Italian,
Portuguese (Portugal and Brazil), Dutch, Polish, Russian, Ukrainian, Swedish, Turkish,
Chinese (simplified and traditional), Japanese, Korean, Hindi and Arabic.

On Android 13 and later you can pick another language for this app only:
**Settings → Apps → Galène Client → Language**.

## Privacy

- No telemetry, no ads, no Google services: the app only talks to the Galène server you
  entered.
- Only the last server and the last user name are kept on the phone, never passwords or
  invitation links. They are excluded from backups.
- The permissions requested are those your entry choice needs: nothing is asked if you
  only listen.
- The code is free software (MIT licence) and can be audited.

## Troubleshooting

**"No answer after 15 s"** — check the server address and your connection. Try the
same address in the phone's browser.

**I see the participants but no picture and no sound** — the network probably blocks
direct connections (common on mobile data and company networks). The server needs a
working TURN server: tell its administrator.

**"This group authenticates through a web portal"** — not supported yet; ask for an
invitation link.

**Others cannot hear me** — check that the microphone button is not crossed out, that
you chose **Microphone** or **Camera + microphone** on entering, and that the
microphone permission is granted (**Settings → Apps → Galène Client → Permissions**).
An operator may have muted you: a message then says so.

**Sound comes out of the wrong device** — pick it with the **Audio output** button at
the top of the screen.

**The call stops when the screen is off** — some manufacturers kill background apps
aggressively. In the battery settings, exempt Galène Client from battery optimisation.
See also [dontkillmyapp.com](https://dontkillmyapp.com).

**Reporting a bug** — on the [issue tracker](https://github.com/antoinelucas64/galeneAndroid/issues),
with your phone model, Android version and what happened.
