# Galène Client — guide utilisateur

*English version: [user-guide.md](user-guide.md)*

Galène Client permet de participer depuis un téléphone Android aux visioconférences d'un
serveur [Galène](https://galene.org) : vidéo, audio, chat, main levée, comme avec le client
web, mais avec un vrai fonctionnement en arrière-plan.

- [Installer](#installer)
- [Rejoindre une réunion](#rejoindre-une-réunion)
- [Pendant la réunion](#pendant-la-réunion)
- [Le chat](#le-chat)
- [Les participants](#les-participants)
- [Pour les opérateurs](#pour-les-opérateurs)
- [Quitter l'appli sans quitter l'appel](#quitter-lappli-sans-quitter-lappel)
- [Réseau et données mobiles](#réseau-et-données-mobiles)
- [Langue](#langue)
- [Vie privée](#vie-privée)
- [En cas de problème](#en-cas-de-problème)

## Installer

Android 8.0 ou plus récent est nécessaire.

- **F-Droid** : la publication est en cours ; l'appli y sera mise à jour automatiquement.
- **Fichier APK** : téléchargez l'APK et ouvrez-le. La première fois, Android demande
  d'autoriser l'installation depuis votre navigateur ou gestionnaire de fichiers.
  Si vous avez le choix, prenez `arm64-v8a` (presque tous les téléphones récents) ;
  la version `universal` fonctionne partout mais pèse trois fois plus.

L'appli ne demande ni compte, ni services Google : elle fonctionne sur LineageOS,
/e/OS, GrapheneOS…

## Rejoindre une réunion

### Avec un lien d'invitation

C'est le plus simple. Copiez le lien reçu, par exemple :

```
https://galene.exemple.org/group/reunion/?token=ABCDEF
```

et collez-le dans le champ **Serveur ou lien d'invitation**, puis **Continuer**.
Le nom d'utilisateur devient facultatif ; il n'est demandé que si le lien l'exige.

### Avec l'adresse du serveur

1. Tapez l'adresse du serveur (`galene.exemple.org`) puis **Continuer**.
2. L'appli affiche les **groupes publics** du serveur, avec le nombre de participants.
   Un groupe non listé se rejoint en tapant son nom dans **Autre groupe (nom)**.
   Un groupe **Verrouillé** n'accepte que ses opérateurs.
3. Remplissez le formulaire du groupe :
   - **Nom d'utilisateur** et **Mot de passe**, si le groupe en demande. Pour un groupe
     relié à un serveur d'authentification (comptes YunoHost par exemple), ce sont vos
     identifiants habituels : ils sont vérifiés par ce serveur.
   - **En entrant, publier** : **Rien** (écouter seulement), **Micro**, ou
     **Caméra + micro**.
4. **Rejoindre**. Android demande alors l'accès au micro et à la caméra selon votre choix,
   et l'autorisation d'afficher une notification (Android 13 et plus).

Le dernier serveur et le dernier nom utilisés sont retenus. Les mots de passe, jamais.

Si le serveur refuse l'entrée (mot de passe faux, groupe complet…), vous revenez au
formulaire avec la raison.

> Certains groupes s'authentifient par un portail web : l'appli ne le gère pas encore.
> Demandez un lien d'invitation à l'organisateur.

## Pendant la réunion

### L'écran

Chaque participant a sa vignette ; celles sans vidéo affichent l'initiale du nom.
Votre propre image est la vignette **Moi**.

- **Toucher une vignette** l'affiche en plein écran ; toucher à nouveau, ou le bouton
  retour, revient à la grille.
- **Contour coloré** autour d'une vignette : cette personne est en train de parler.
- **✋ sur une vignette** : cette personne a levé la main.

### Les boutons

En bas, de gauche à droite :

| Bouton | Rôle |
|---|---|
| 🎤 Micro | Couper / réactiver votre micro |
| 📷 Caméra | Couper / réactiver votre caméra (elle s'éteint vraiment, voyant compris) |
| ✋ Main | Lever / baisser la main ; le bouton reste coloré tant que la main est levée |
| 💬 Chat | Ouvrir les messages ; une pastille compte les messages non lus |
| 📞 rouge | Quitter la réunion |

En haut :

| Bouton | Rôle |
|---|---|
| ← | Quitter la réunion |
| 🔄 Changer de caméra | Caméra avant / arrière |
| 🔊 Sortie audio | Choisir où va le son (voir ci-dessous) |
| 👥 Participants | La liste des participants ; une pastille compte les mains levées |

Les boutons micro et caméra sont grisés si vous ne publiez pas ce média (choix
**Rien** ou **Micro** à l'entrée, ou autorisation refusée).

### Lever la main

Touchez ✋. Tous les participants le voient, y compris ceux sur le client web (une
icône à côté de votre nom dans leur liste). Quand quelqu'un d'autre lève la main, un
message « … lève la main » s'affiche en bas de l'écran.

Depuis le client web, on lève la main en tapant `/raise` dans le chat (`/unraise` pour
la baisser).

### Le son : haut-parleur, écouteur, casque, Bluetooth

Le son va automatiquement au meilleur appareil disponible : casque Bluetooth, sinon
casque filaire ou USB, sinon haut-parleur. Brancher ou connecter un casque en cours de
réunion bascule le son dessus, micro compris.

Le bouton **Sortie audio** permet de choisir : **Haut-parleur**, **Écouteur du
téléphone** (le téléphone à l'oreille), **Casque** ou **Bluetooth**.

### Appel téléphonique entrant

Un appel téléphonique interrompt le son de la réunion ; il reprend dès que vous
raccrochez.

## Le chat

Touchez 💬. Les messages s'affichent en bulles, les vôtres à droite. Les liens sont
cliquables. En arrivant dans un groupe, vous voyez les messages déjà échangés.

Quand le chat est fermé, un nouveau message apparaît quelques secondes en haut des
vidéos ; touchez-le pour ouvrir le chat.

- **Message privé** : au-dessus du champ de saisie, **À : Tout le monde ▾** permet de
  choisir un participant. Le message n'est vu que par lui. Pour répondre à un message
  privé reçu, touchez-le : son auteur devient le destinataire.
- **Action** : `/me salue tout le monde` s'affiche « * Antoine salue tout le monde ».
- Les **messages des opérateurs et du serveur** (avertissements, informations)
  s'affichent au centre.

## Les participants

Touchez 👥 en haut. La liste montre les mains levées en premier, puis vous, puis les
autres par ordre alphabétique. L'initiale s'allume quand la personne parle ; les
opérateurs sont indiqués.

Le menu **⋮** d'un participant propose **Message privé**, qui ouvre le chat déjà
adressé à cette personne.

## Pour les opérateurs

Si vous êtes opérateur du groupe, le menu **⋮** de chaque participant propose aussi les
actions du client web :

- **Couper micro** : son micro est coupé (il peut le réactiver) ;
- **Autoriser à présenter** / **Interdire de présenter** : le droit d'envoyer audio et
  vidéo ;
- **Nommer opérateur** / **Retirer les droits d'opérateur** ;
- **Exclure du groupe**, après confirmation.

Inversement, un opérateur peut couper votre micro (un message vous le signale), effacer
le chat, ou vous exclure de la réunion.

## Quitter l'appli sans quitter l'appel

- **Bouton d'accueil ou geste de retour à l'accueil** : la réunion se réduit en
  **petite fenêtre flottante** (Picture-in-Picture) qui montre la personne qui parle.
  Elle a deux boutons : micro et raccrocher. Touchez la fenêtre pour revenir en plein
  écran, faites-la glisser pour la déplacer.
- **Fenêtre fermée ou autre appli au premier plan** : la réunion continue en
  arrière-plan, avec le son. Votre caméra se coupe pour ne pas filmer à votre insu, et
  se rallume à votre retour. Une notification **Appel en cours** permet de revenir à
  tout moment.
- L'écran ne se met pas en veille pendant la réunion.

Pour quitter vraiment : le bouton rouge, la flèche ←, ou raccrocher dans la petite
fenêtre.

## Réseau et données mobiles

- **Changement de réseau** (Wi-Fi → 4G en sortant de chez vous, coupure brève) :
  l'appli se reconnecte d'elle-même et affiche **Reconnexion…**. Micro et caméra
  restent prêts ; l'image et le son des autres reviennent après quelques secondes.
  Après deux minutes sans réseau, elle abandonne et vous le dit.
- **Données mobiles** : l'appli demande automatiquement une vidéo plus légère aux
  autres participants, pour économiser votre forfait. Elle repasse en qualité normale
  en Wi-Fi. (Cela marche quand l'expéditeur envoie deux qualités, comme le fait le client
  web.)

## Langue

L'appli suit la langue du téléphone : français, anglais, allemand, espagnol, italien,
portugais (Portugal et Brésil), néerlandais, polonais, russe, ukrainien, suédois, turc,
chinois (simplifié et traditionnel), japonais, coréen, hindi et arabe.

Sur Android 13 et plus, vous pouvez choisir une autre langue pour cette seule appli :
**Paramètres → Applications → Galène Client → Langue**.

## Vie privée

- Aucune télémétrie, aucune publicité, aucun service Google : l'appli ne contacte que
  le serveur Galène que vous avez indiqué.
- Seuls le dernier serveur et le dernier nom d'utilisateur sont retenus sur le
  téléphone, jamais les mots de passe ni les liens d'invitation. Ils sont exclus des
  sauvegardes.
- Les autorisations demandées sont celles de votre choix d'entrée : rien n'est demandé
  si vous écoutez seulement.
- Le code est libre (licence MIT) et vérifiable.

## En cas de problème

**« Pas de réponse après 15 s »** — vérifiez l'adresse du serveur et votre connexion.
Essayez la même adresse dans le navigateur du téléphone.

**Je vois les participants mais ni image ni son** — le réseau bloque probablement la
connexion directe (fréquent en 4G et sur les réseaux d'entreprise). Le serveur doit
disposer d'un serveur TURN qui fonctionne : signalez-le à son administrateur.

**« Ce groupe s'authentifie via un portail web »** — non pris en charge pour l'instant ;
demandez un lien d'invitation.

**Les autres ne m'entendent pas** — vérifiez que le bouton micro n'est pas barré, que
vous avez choisi **Micro** ou **Caméra + micro** en entrant, et que l'autorisation
micro est accordée (**Paramètres → Applications → Galène Client → Autorisations**).
Un opérateur a peut-être coupé votre micro : un message l'indique alors.

**Le son sort du mauvais appareil** — choisissez-le avec le bouton **Sortie audio** en
haut de l'écran.

**L'appel se coupe quand l'écran est éteint** — certains constructeurs arrêtent les
applis en arrière-plan de façon agressive. Dans les paramètres de batterie, retirez
Galène Client de l'optimisation de batterie. Voir aussi
[dontkillmyapp.com](https://dontkillmyapp.com).

**Signaler un bug** — sur le [suivi des tickets](https://github.com/antoinelucas64/galeneAndroid/issues),
en indiquant le modèle du téléphone, la version d'Android et ce qui s'est passé.
