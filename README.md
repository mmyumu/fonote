# Fonote

## Compositions ESPN (usage personnel)

Le calendrier utilise la clé `FOOTBALL_DATA_TOKEN` du `.env`. À l’ouverture d’un match,
le serveur cherche sa composition sur le flux public ESPN, sans clé supplémentaire.
Le rapprochement exige la même compétition, les deux équipes domicile/extérieur et un
horaire proche ; une correspondance ambiguë est refusée. Les 22 titulaires doivent être
présents et distincts. Leurs identifiants `espn-…` restent séparés des identifiants football-data.
Les couleurs des maillots viennent aussi d’ESPN : elles sont éclaircies pour que le numéro
reste lisible, et l’équipe visiteuse bascule sur sa couleur alternative si les deux se
ressemblent trop. Un cache mémoire limite les appels ESPN : cinq minutes en temps normal, trente secondes pour un
match en cours — nettement moins que la minute qui sépare deux rafraîchissements du client, faute
de quoi un poll sur deux recevrait la copie qu'il a déjà. L'identifiant de l'événement ESPN est
retenu après la première résolution, donc rafraîchir un match coûte une requête et non deux :
le `scoreboard` ne sert qu'à répondre « quel événement est-ce ? », et la réponse ne change pas.
ESPN déclare lui-même son flux périmé au bout de neuf secondes, ce rythme reste donc très en deçà
de ce que son propre cache anticipe.

Le serveur rapporte aussi le déroulé du match — remplacements, buts, cartons, coup d’envoi et
mi-temps réels — dans `timeline` et `clock`, les remplaçants dans `bench`, les compteurs
d’équipe dans `team_stats`, le stade, l’arbitre et l’affluence dans `ground`, et les compteurs
par joueur dans le `stats` de chaque ligne de composition. Le serveur transmet les clés brutes
du fournisseur (`possessionPct`, `wonCorners`…) : les nommer en français est l’affaire du client,
comme pour les codes de compétition. Rien de tout cela n’entre dans le journal d’opérations :
les faits du fournisseur s’affichent à côté des notes, jamais dedans, pour que le bilan continue
de ne mesurer que ce qui a été relevé.

Les compositions indisponibles ou les erreurs du fournisseur n’empêchent plus d’ouvrir
les notes générales. Les positions sur le terrain sont schématiques, pas des positions
tactiques garanties, mais chaque joueur est placé d’après le poste publié par ESPN
(`Center Left Defender`, `Attacking Midfielder Left`…) : la profondeur vient de la famille du
poste, le côté du qualificatif. Les côtés sont dessinés tels qu’on les voit, pas tels qu’on les
nomme : l’équipe à domicile attaque vers le bas, donc la gauche d’un joueur est la droite de
l’image — l’arrière droit se dessine à gauche du terrain, comme `match.json` l’écrit depuis
toujours pour la finale 2018 (Pavard à 0,13, Hernandez à 0,87). L’équipe visiteuse est retournée. Le `formationPlace` ne sert pas au placement — c’est la
numérotation classique des maillots, où le 11 est un ailier et le 9 l’avant-centre, pas un ordre
des lignes ; s’y fier mettait un milieu offensif gauche en pointe. Les lignes dessinées sont donc
celles que les postes décrivent, et elles retrouvent la formation annoncée dans la grande
majorité des cas ; l’écart restant est un regroupement du front de l’attaque, jamais un joueur
du mauvais côté. Un poste dont le libellé est inconnu fait retomber l’équipe sur la formation
publiée, et à défaut sur le 4-4-2, plutôt que d’éparpiller les onze. ESPN est un flux non contractuel : sa disponibilité peut changer.
Cette intégration est destinée à l’usage personnel demandé, sans garantie de couverture.
Après modification du serveur, le redémarrer — ou le lancer d’emblée avec `--reload`, qui s’en charge.

Premier prototype Android de prise de notes football, avec serveur personnel commun au futur client Quest. Aucun abonnement ni API à quota : la composition fournie est celle de la finale France–Croatie du 15 juillet 2018, extraite hors ligne de [StatsBomb Open Data](https://github.com/statsbomb/open-data) (match 8658) vers `android/app/src/main/assets/match.json`. Usage personnel ; ces données ne sont pas redistribuées.

## Ce qui fonctionne dans le code

- Accueil, calendrier, suivis et options suivent les conventions Android : titre et actions dans une barre en haut (retour à gauche, ☆ suivis et ⚙ options à droite) au lieu de gros boutons dans le contenu, retour tactile sur chaque surface cliquable, barres système à la couleur de la page. Un match se lit comme un match : heure locale (plus d'UTC) ou état en cours à gauche, les deux équipes et leur score au centre, la compétition en dessous. Le calendrier groupe ses rencontres par jour ; une liste vide propose d'aller choisir ses suivis. Les codes du fournisseur sont traduits (« REGULAR_SEASON » devient « Championnat · J3 »). Les icônes (roue, étoile, flèches) sont des `VectorDrawable` du projet, dans `android/app/src/main/res/drawable/` : les `android.R.drawable.ic_menu_*` de la plateforme sont des images matricielles d'avant Material qui changent d'un constructeur à l'autre, et un glyphe de police comme ⚙ tombe sur l'emoji du système.
- Terrain avec les 22 titulaires placés à leur poste réel, chaque équipe dans sa formation (4-4-2 / 4-3-3).
- **Faits du match**, une carte posée à droite du terrain : on l'amène en balayant le doigt vers
  la gauche, on revient en balayant vers la droite, et le geste retour fait la même chose avant de
  toucher à la note. Elle porte le score, le fil du match (buts avec passeur, cartons,
  remplacements, à la minute et aux couleurs de l'équipe), puis le stade et l'arbitre. Un rappel
  « Faits › » en bout de ligne d'état l'annonce et l'ouvre aussi d'un doigt, sans coûter une
  rangée de hauteur.
  Le pager est écrit dans le projet (`Pager.java`) : le client ne porte aucune dépendance
  d'interface, et ce qu'un pager ajoute à un défilement horizontal tient en une accroche et la
  notion de page affichée. Une page tourne au cinquième de l'écran parcouru, pas à la moitié.
  Les rangées qui défilent latéralement — la palette d'actions, les joueurs d'une note — prennent
  le geste pour elles en l'interceptant avant leurs propres boutons, sinon ceux-ci consomment
  l'appui et la carte tournerait au lieu de faire défiler la palette ; une rangée qui tient déjà
  entière dans l'écran laisse au contraire passer le geste. Le geste retour ne saute pas au terrain :
  il défait une carte à la fois. Page séparée et annoncée comme telle — « rien ici n'entre
  dans votre journal ni dans votre bilan » — parce que le bilan promet de ne mesurer que ce qui a
  été relevé, et qu'un but compté par le fournisseur n'est pas une observation.
  L'affluence est tue quand elle vaut zéro, ce qui veut dire « inconnue » et non « personne ».
- **Statistiques**, une troisième carte posée à droite des faits : un balayage de plus vers la
  gauche amène les compteurs des deux équipes en vis-à-vis, sous le nom de chacune et à sa couleur.
  Page à part parce qu'une colonne de chiffres se parcourt du regard quand un fil de match se suit
  ligne à ligne, et titrée « Statistiques » — ce que la carte montre, pas qui le fournit ; la
  provenance tient dans la ligne grise dessous, avec le même rappel que rien de tout cela n'entre
  dans le journal ni dans le bilan. Sur les 28 chiffres publiés, 13 sont montrés : les pourcentages
  dérivés ne font que répéter le couple au-dessus d'eux. Un match dont le fournisseur ne raconte
  rien mais compte quand même ouvre cette carte directement depuis le terrain.
- Pendant un match suivi, l'application redemande la composition **une fois par minute**, et seulement
  là où c'est utile : écran du match, depuis une heure avant le coup d'envoi jusqu'à la 140ᵉ minute,
  jamais pendant qu'une note est ouverte — les joueurs ne doivent pas bouger sous le doigt. Un
  rafraîchissement qui échoue est un rafraîchissement manqué, pas un message d'erreur : les notes
  sont locales et la minute suivante réessaie.
- Le terrain suit le match : un remplacement publié fait entrer le joueur à la minute dite, sur la place
  de celui qu'il remplace, et le sortant quitte la pelouse. Les notes déjà écrites gardent leurs joueurs —
  le journal n'est jamais redessiné, et un joueur sorti reste retirable d'une note en cours par la croix
  du panneau. Un enchaînement de changements sur la même place se transmet ; un changement qui nomme
  quelqu'un d'absent est ignoré, pour qu'un flux qui se contredit ne puisse jamais vider une place.
- Deux styles de pastille au choix dans **Accueil → Options** : « Verre » (disque sombre, couleur de l'équipe en anneau, en halo et sur le numéro) ou « Plein » (disque peint). Le choix est montré par un aperçu dessiné avec le même code que le terrain, et il est conservé d'une session à l'autre. Le nom de chaque joueur porte sa propre plaque, dessinée sur une couche à part, au-dessus de **toutes** les pastilles : sur une formation à cinq lignes, une demi-pelouse ne peut pas loger six rangées sans chevauchement, donc la question n'est pas de l'éviter mais de choisir qui l'emporte — un nom reste toujours lisible, un numéro à moitié couvert garde sa couleur et sa place. Seul le joueur qu'on est en train de noter passe devant les noms, pour que lire un voisin ne coûte jamais le numéro de celui qu'on note. La cible tactile est la pastille elle-même (44 dp) et non la boîte qui portait le nom : deux voisins sont moins faciles à confondre du doigt.
- Deux gestes pour noter : toucher un joueur, puis son action. Il n'y a pas de bouton d'enregistrement — chaque action écrit la note aussitôt.
- Une note décrit un moment, pas un joueur. Tant qu'une note est ouverte, le panneau **collecte** : chaque joueur touché la rejoint avec son action propre, et « A marque, B délivre la passe décisive, C rate son arrêt » s'enchaîne d'un trait en six taps, sans quitter le terrain. Sans note ouverte, toucher un joueur en démarre une. Le seul geste qui reste à dire tout haut est donc la fin d'un moment : **« Terminé »**, qui rend la liste des notes. La corbeille à côté jette la note en cours ; tant qu'aucune action n'a été choisie il n'y a rien à jeter, et le bouton dit « Abandonner ».
- **Note tactique**, le second mode de prise de note, ouvert par « ▤ Tactique » à côté de
  « + Note sans joueur ». Le mode rapide répond à « qui a fait quoi, et à quelle minute » ; celui-ci
  répond à « où, et vers qui » — une passe mérite d'être dessinée quand ce qui compte est la ligne
  qu'elle a prise et les joueurs qu'elle a éliminés, et aucune palette de dix-huit symboles ne dit
  cela. C'est **la même note** en dessous : même identifiant, même minute, même commentaire, même
  récapitulatif, et les actions données ici comptent dans le bilan exactement comme celles tapées
  sur le terrain. Seule la surface change, et elle prend tout l'écran — un tableau qui partage la
  place avec un panneau est un tableau sur lequel on ne peut pas dessiner.
  - Deux plateaux : **terrain vierge**, où l'on pose les trois ou quatre joueurs qui comptent
    (« + Joueur » les place à leur poste réel, y compris un remplaçant à la place qu'il occupe à
    cette minute, et la suite du geste est une correction plutôt qu'un placement à partir de rien),
    et **terrain complet**, les 22 dans leur formation, qu'on déplace. On passe de l'un à l'autre à
    tout moment ; vider le terrain se rattrape par `↶`. Un pion sans nom — « Pion — Strasbourg »,
    « Pion — sans équipe » — sert pour l'adversaire dont le seul rôle est d'avoir été éliminé.
  - Deux outils explicites plutôt qu'un geste malin : `↔ Déplacer` traîne les joueurs, les quatre
    tracés dessinent. Un doigt qui glisse est ambigu — « il était plus à gauche » ou « le ballon est
    parti là-bas » — et deviner faux coûte soit un tracé perdu, soit un joueur déplacé qui était
    bien placé. Le dire coûte un tap avant une série de tracés et ne coûte jamais une erreur.
  - **Déplacement par lots.** En mode Déplacer, glisser sur la pelouse **vide** encadre : tous les
    joueurs pris dans le rectangle sont sélectionnés, et glisser l'un d'eux les emmène tous. Un
    schéma est très souvent un bloc — une défense qui remonte, un milieu qui coulisse — et les
    bouger un par un est la façon la plus sûre de renoncer au dessin. Le geste était libre : dans
    ce mode, un glissé sur l'herbe ne faisait rien. Le déplacement est **rigide** : la course est
    bornée une fois pour tout le groupe et non joueur par joueur contre la ligne de touche, sinon
    une défense poussée vers le corner s'écraserait contre le bord au lieu de garder sa forme.
    Prendre quelqu'un hors du groupe relâche le groupe ; toucher l'herbe relâche tout. Le panneau
    dit ce que l'outil en main sait faire quand rien n'est sélectionné, et sa hauteur ne change
    jamais — choisir un outil ou un joueur ne déplace pas le terrain sous le doigt.
  - **Appui long sur un joueur** : il entre dans la sélection ou en sort. Un rectangle est un outil
    grossier — il attrape le milieu défensif qui se tenait entre les lignes visées — et le
    redessiner pour corriger un homme coûte plus cher que corriger cet homme. Le groupe n'est
    donc touché qu'une fois que le doigt a dit ce qu'il voulait : remplacer la sélection dès
    l'appui ferait perdre tous les autres à un appui long destiné à en retirer un seul. La durée
    est comptée dans la vue (`ViewConfiguration.getLongPressTimeout()`) : elle traite elle-même
    chaque événement, donc l'appui long que la plateforme aurait programmé ne l'est jamais.
    Le seuil de déplacement n'est pas du trajet — le glissé part de l'endroit où le toucher a
    cessé d'être un appui, et rien ne bouge avant : un tremblement sous un doigt posé n'est pas
    un glissé, sinon il emporterait la sélection avant que l'appui long ait le temps de parler.
    Attention, le geste ne dit pas la même chose que sur le terrain du mode rapide, où l'appui
    long **retire** le joueur de la note ; ici retirer un pion se fait à la gomme ou par le `×` du
    panneau, et l'appui long ne touche qu'à la sélection.
  - Les bords du tableau sont réservés à l'application (`setSystemGestureExclusionRects`) : un
    latéral se tient sur la ligne de touche et un groupe s'encadre depuis l'extérieur, deux gestes
    qui partent là où le système lit un balayage retour — glisser Maronnier vers la gauche quittait
    la note au lieu de le déplacer. La plateforme plafonne à 200 dp par bord ce qu'une application
    peut réclamer, donc les glissés les plus près du cadre sont protégés, pas tous.
  - Quatre tracés, distingués par la **forme** du trait et jamais par sa couleur — la couleur nomme
    déjà une équipe : passe (trait plein), course sans ballon (pointillés), conduite de balle
    (ondulé), tir (trait double). Chacun part d'un ballon sauf la course, qui se fait sans lui.
    Le trait suit le doigt : un glissé droit donne une droite, un glissé courbe garde sa courbe.
  - Les deux bouts d'un tracé **s'aimantent** au joueur qui se trouve à côté, donc « de Ripart vers
    Yassine » est exact sans viser au pixel ; le trait est ensuite reculé du disque qu'il touche,
    sinon la pointe de flèche disparaît sous le joueur qu'elle désigne. Un tracé garde les
    coordonnées avec lesquelles il a été dessiné : un schéma enregistre où le ballon est allé à un
    instant, pas un lien qui suivrait un joueur.
  - Toucher un joueur du tableau ouvre la palette complète pour lui, ou « Aucune action ». Ce qui
    est sur le tableau **est** la note : gommer un joueur emporte l'action qu'on lui avait donnée.
  - Pas de bouton d'enregistrement ici non plus : chaque tracé, chaque déplacement, chaque gomme
    écrit aussitôt. Chacun des trois éléments d'une note — les participants, le texte, le schéma —
    n'est réémis que s'il a réellement changé, sinon renvoyer les participants à chaque trait
    enterrerait le journal sous des versions qui disent la même chose.
- Dans **« Mes observations »**, une note dessinée est montrée dessinée : « ↗ Bonne passe — 20 Ripart »
  ne dit presque rien d'un moment dont tout l'intérêt était la ligne prise par le ballon. La carte
  porte donc le tableau lui-même, en petit, et la toucher rouvre le tableau — la boîte de dialogue
  que toute autre note ouvre au toucher est ici sur l'appui long.
- Le résumé d'une note suit toujours le même ordre, quel que soit l'ordre de saisie : le plus fort d'abord, le bon avant le mauvais, départages par l'ordre de la palette. Il se déduit des poids d'actions, sans table supplémentaire, et s'applique au rendu seulement — le journal garde l'ordre réel de saisie, donc changer d'avis sur cet ordre ne réécrit aucune note.
- Le geste retour défait l'écran courant au lieu de quitter : il ferme la note ouverte en la gardant, puis ramène de « Notes » ou « Bilan » au match, et ne sort de l'application qu'en dernier recours.
- Retirer un joueur de la note se fait par **appui long sur le terrain**, là où on l'a mis — la croix de sa pastille fait la même chose. Un tap sur un joueur déjà dans la note vise sa ligne pour corriger son action, ce qui interdisait le double-clic. Retirer le dernier joueur d'une note l'efface.
- Une note laissée ouverte pendant que le match avance est le seul risque de la collecte : passé deux minutes, sa pastille de minute vire à l'ambre.
- La provenance de la composition tient dans la ligne d'état sous le bandeau (« 4-1-2-1-2 / 4-2-3-1 · ESPN, placement schématique · Ligue 1 ») au lieu d'occuper une ligne à elle seule en haut de l'écran : la place ainsi rendue va au terrain, qui en manque. Seule l'absence de composition mérite encore une phrase, et un terrain vide a toute la place pour la porter.
- Composition dans un bandeau fixe sous le terrain, jamais en surimpression : le terrain reste visible et cliquable. Sa hauteur ne varie pas, pour qu'ouvrir une note ne déplace jamais les joueurs sous le doigt.
- Dix-huit actions appariées geste par geste, réussi au-dessus, raté en dessous : bon/raté, but/CSC, passe décisive/perte de balle, passe/passe ratée, dribble/dribble raté, tir cadré/tir manqué, geste défensif/duel perdu, arrêt/arrêt raté, jaune, rouge. Chacune porte un poids ; polarité, couleur et bilan en découlent, au lieu d'être choisis à la main.
- Sur chaque carte du bilan, une ligne grise porte ce que le fournisseur a compté pour ce joueur —
  buts, tirs, arrêts, fautes, cartons — à côté de la note et jamais dedans. Seuls les compteurs non
  nuls sont écrits : une ligne de douze zéros ne dit rien. Les compteurs qui décrivent l'équipe et
  non le joueur (buts encaissés, tirs subis, arrêts) ne sont montrés qu'au gardien : ESPN les porte
  sur la ligne de tout le monde, et « 1 encaissé » sur un attaquant se lit comme sa faute. Un
  gardien entré en cours de jeu, que le fournisseur n'appelle que « Substitute », est reconnu à ce
  qu'on lui a compté.
- Bilan par joueur déduit des notes : solde signé sur le maillot au repos, page dédiée avec une note sur dix (base 6, une demi-note par point), le détail qui la justifie et le rappel qu'elle ne mesure que ce qui a été relevé.
- Chronomètre automatique depuis le coup d'envoi **réel** lorsque le fournisseur le publie, et non l'horaire
  annoncé : un coup d'envoi retardé de dix minutes décalait sinon toutes les notes du match. La minute de
  la note est préremplie. Ajustable sur la minute de la diffusion, pause mi-temps, coup d’envoi à 0′, reprise à 45′ ; il survit à la fermeture de l'application.
- Historique, commentaires facultatifs, suppression.
- `↶` annule la dernière opération, quelle qu'elle soit, et remonte le journal pas à pas : une suppression est défaite, une note complétée revient à sa version précédente, un commentaire au texte qu'il portait. Rien n'est retiré du journal — l'annulation écrit l'opération qui compense, donc elle se synchronise comme le reste. Toute nouvelle action replace le curseur à la fin.
- SQLite sur Android ; enregistrement hors connexion, conservé après fermeture de l'application.
- Synchronisation manuelle avec un serveur SQLite, authentifié par un jeton personnel.
- Export JSON des observations via le partage Android.

Le client est écrit en Java avec les widgets Android natifs, sans dépendance d'interface. Kotlin/Compose évoqué au cadrage n'est pas utilisé dans ce prototype. Aucun SDK fournisseur n'est couplé aux notes.

## Changer de match

`android/app/src/main/assets/match.json` est produit hors ligne, une fois, par un script sans dépendance :

```bash
python3 scripts/fetch-match.py 8658 --id wc2018-final \
  --competition "Coupe du monde 2018" --stage Finale --date 2018-07-15 \
  > android/app/src/main/assets/match.json
```

L'argument est un identifiant [StatsBomb Open Data](https://github.com/statsbomb/open-data) ; les couleurs d'équipe viennent de TheSportsDB (libre, sans clé). Les couleurs officielles sont inutilisables telles quelles sur la pelouse sombre — le marine des Bleus y tombe à 1,02:1 de contraste — donc le script conserve la teinte et remonte la clarté jusqu'à un seuil lisible, avec une cible différente par équipe pour qu'elles se distinguent aussi par la clarté et pas seulement par la teinte. `colourOfficial` garde la valeur d'origine.

Les positions viennent d'une table poste → point tenue dans le script : juste sur les formations classiques, à vérifier sur les systèmes plus rares.

## Démarrer le serveur

Python 3.12 ou plus récent, sans dépendance à installer. Depuis la racine :

```bash
export FONOTE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
python3 backend/server.py --db fonote.sqlite3
```

Pendant le développement, `--reload` relance le serveur à chaque sauvegarde d’un fichier de
`backend/` — sans dépendance, un processus surveille les dates de modification et redémarre son
enfant. Le processus qui surveille n’importe jamais ce qu’il surveille : une faute de frappe ne
tue que l’enfant, la trace s’affiche dans le terminal, et la sauvegarde suivante remet un serveur
qui marche. Un redémarrage vide au passage le cache mémoire ESPN et relit le `.env`, ce qui est
précisément l’intérêt.

```bash
python3 backend/server.py --db fonote.sqlite3 --reload
```

Qui préfère un outil déjà connu obtient la même chose sans rien ajouter au dépôt, `uvx` lançant
le surveillant dans un environnement jetable :

```bash
uvx watchfiles 'python3 backend/server.py --db fonote.sqlite3' backend
```

La détection y est immédiate plutôt que sondée chaque seconde, au prix d'un premier lancement
qui a besoin du réseau. `uvicorn --reload`, lui, ne s'applique pas : il lui faut une application
ASGI, et ce serveur est bâti sur `http.server`.

Configurer le même jeton dans l'application. Le serveur écoute uniquement sur `127.0.0.1:8080` par défaut. Pour un téléphone connecté en USB :

```bash
adb reverse tcp:8080 tcp:8080
```

Pour activer l’accueil et le calendrier des vrais matchs, créer une clé gratuite sur
[football-data.org](https://www.football-data.org/client/register), puis la fournir uniquement au serveur :

```bash
export FOOTBALL_DATA_TOKEN="votre-cle-football-data"
```

Le serveur charge aussi automatiquement le fichier `.env` à la racine du projet,
quel que soit le dossier de lancement. Ajouter `FOOTBALL_DATA_TOKEN=votre-cle-football-data`
dans ce fichier puis redémarrer le serveur. Les variables déjà exportées ont priorité.
Le fichier `.env` est ignoré par Git ; son contenu n’est jamais exécuté comme du code.

Le serveur sert de proxy limité aux compétitions, équipes et matchs. La clé fournisseur n’est donc
jamais enregistrée dans l’application. Sans cette variable, l’application conserve son mode démo hors ligne.

Utiliser alors `http://127.0.0.1:8080` dans l'application **debug**. Pour l'émulateur Android : `http://10.0.2.2:8080`.

Le jeton est un secret : ne pas le committer. La version release refuse HTTP en clair. Un déploiement distant doit passer par HTTPS et un serveur HTTP de production ; le serveur Python fourni est destiné au prototype personnel local. Un seul espace personnel par serveur, pas de gestion de comptes.

## Construire Android

Sur cette machine WSL, les outils sont installés dans `.tooling/` (ignoré par Git) : JDK 17, Gradle 8.9, SDK 35, Build-Tools 34, adb, Android Studio et émulateur. Depuis la racine :

```bash
bash scripts/build-android.sh
bash scripts/android-studio.sh
```

Le premier script compile et lance Lint ; le second ouvre Android Studio sur le projet. Le Wrapper Gradle est fourni avec contrôle SHA-256. Sur une autre machine avec Java 17 et le SDK configurés, utiliser `cd android && ./gradlew :app:assembleDebug` (ou `gradlew.bat` sous Windows).

APK : `android/app/build/outputs/apk/debug/app-debug.apk`. L'application cible actuellement API 34 pour ce prototype et fonctionne à partir d'Android 8 (API 26) ; publication Play Store hors périmètre.

Pour utiliser les outils dans un terminal :

```bash
source scripts/android-env.sh
adb devices -l
```

L'émulateur `Fonote_API_35` utilise l'image Google APIs Android 35. Sous WSL, l'accélération exige l'accès à `/dev/kvm`. Si nécessaire, exécuter `sudo usermod -aG kvm mmyumu`, puis ouvrir une session avec les nouveaux groupes (`newgrp kvm`) avant de lancer :

```bash
bash scripts/run-emulator.sh
```

Après démarrage de l'émulateur ou connexion d'un téléphone autorisé :

```bash
source scripts/android-env.sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n fr.fonote/.MainActivity
```

Avec plusieurs appareils, ajouter `-s <identifiant>` à adb. Pour un téléphone USB sous WSL, le périphérique doit d'abord être transmis par Windows (usbipd), ou utiliser adb Windows. L'absence d'appareil dans `adb devices` n'empêche pas la compilation.

Compatibilité de l'outillage : [AGP 8.7 / Gradle 8.9 / JDK 17](https://developer.android.com/build/releases/agp-8-7-0-release-notes). HTTP debug et HTTPS release reposent sur la [configuration de sécurité Android](https://developer.android.com/privacy-and-security/security-config).

## Vérifier

```bash
python3 -m unittest discover -s backend -v
source scripts/android-env.sh
javac -d /tmp/fonote-checks android/app/src/main/java/fr/fonote/{Formation,Lineup,MatchClock,PlayerName}.java android/checks/fr/fonote/*.java
for check in Formation Lineup MatchClock PlayerName; do java -ea -cp /tmp/fonote-checks fr.fonote.${check}Check; done
```

La logique sans Android (placement, chronomètre, noms) tient dans des classes à part, vérifiées
par ces `assert` sans émulateur ni dépendance de test.

Parcours Android à vérifier sur appareil : composer une note à plusieurs joueurs sans quitter le terrain, une note générale par `+`, une note tactique (remplir le terrain, déplacer un joueur, tracer une passe puis une course, encadrer une ligne entière et la déplacer d'un bloc, donner une action à un joueur du tableau, vérifier qu'elle apparaît au bilan, revenir par « Mes observations » et rouvrir le schéma d'un toucher), noter hors connexion, fermer et rouvrir en cours de composition, ajouter un commentaire, vérifier le bilan, synchroniser deux fois, annuler puis resynchroniser, importer sur un second appareil. Les notes ne doivent pas être dupliquées ou réapparaître après suppression.

## Contrat du serveur et persistance

Toutes les routes exigent `Authorization: Bearer <jeton>` :

| Route | Réponse / effet |
|---|---|
| `GET /v1/matches` | Le match embarqué, ses 22 joueurs et sa provenance |
| `GET /v1/football/competitions` | Compétitions accessibles avec le plan football-data.org configuré |
| `GET /v1/football/competitions/{code}/teams` | Équipes d’une compétition, pour choisir ses suivis |
| `GET /v1/football/matches?dateFrom=…&dateTo=…` | Matchs d’une période pour l’accueil et le calendrier |
| `GET /v1/football/matches/{id}` | Détail, composition publiée, remplaçants, déroulé du match et heures réelles |
| `POST /v1/operations` | Enregistre une opération ; renvoie `{seq, operation}` |
| `GET /v1/operations` | Journal complet ordonné, utilisable par Android ou Quest |

Une opération possède `id` (UUID), `note_id` (UUID) et `kind`. Étendre une note en réémet une sous le même `note_id` avec un `id` neuf : le journal garde les deux, le lecteur retient la dernière et conserve la place chronologique de la première.

- `note` : `match_id`, `minute` (entier) et `entries`, la liste des `{player_id, action}` du moment — un joueur au plus une fois, liste vide pour une note générale. Les notes écrites avant cette forme portaient `player_id` et `action` à la racine ; le journal étant immuable, le serveur et le client acceptent toujours les deux.
- `comment` : `text` (2 000 caractères maximum).
- `delete` : aucun champ supplémentaire.
- `restore` : aucun champ supplémentaire ; rend visible une note supprimée.
- `diagram` : `schema`, le tableau d'une note tactique — `board` (`blank` ou `full`), `tokens` et
  `shapes`. Écrit à côté de la note sous le même `note_id`, comme un commentaire : une note dessinée
  est une note qui se trouve aussi être dessinée, et le journal ne connaît qu'une sorte de note.
  Un pion vaut `{player_id, x, y}` pour un joueur du match, ou `{team, label?, x, y}` avec `team`
  parmi `home`, `away`, `neutral` pour un pion sans nom ; un tracé vaut `{kind, points}` avec `kind`
  parmi `pass`, `run`, `carry`, `shot` et 2 à 32 couples de coordonnées. Les coordonnées sont des
  fractions du terrain (0 à 1), l'équipe recevante attaquant vers le bas — le repère dans lequel
  `match.json` écrit déjà sa composition. Bornes : 30 pions, 40 tracés. Volontairement de la
  géométrie et rien d'autre : un pion nomme un joueur et s'arrête là, donc rien ici ne peut
  contredire la composition, le bilan ou le journal sur qui il est ou sur ce qu'il a fait.

Un nouvel envoi du même identifiant et contenu est sans effet. Un contenu différent sous le même identifiant est refusé. Les opérations sont immuables. Le client transmet d'abord ses opérations en attente, puis récupère le journal commun. Une suppression l'emporte sur toute modification concurrente : réécrire une note supprimée ne la fait pas réapparaître, seule une opération `restore` explicite le fait. Pour une note comme pour un commentaire, la dernière version reçue par le serveur prévaut. Avant synchronisation, les opérations locales en attente sont appliquées après celles du serveur.

Le journal conserve les commentaires précédents et marque les notes supprimées comme masquées : supprimer une note dans l'interface n'est **pas** un effacement physique. Un effacement définitif et la purge des appareils nécessiteront un mécanisme dédié. Les UUID de notes ne doivent pas être réutilisés. Le prototype récupère tout le journal : pagination nécessaire si son volume augmente.

Pour sauvegarder la base serveur en cours d'utilisation, utiliser l'API SQLite de sauvegarde (ne pas copier seulement le fichier principal pendant des écritures WAL) :

```bash
python3 -c "import sqlite3; source=sqlite3.connect('fonote.sqlite3'); target=sqlite3.connect('fonote-backup.sqlite3'); source.backup(target); target.close(); source.close()"
```

## Prochaines étapes

Validation sur téléphone et matchs personnels. Les suivis et les statistiques du fournisseur sont en place ; l’interface Quest ne l’est pas encore. Aucune donnée de fournisseur n'est collectée ni archivée à ce stade ; les notes originales restent indépendantes de leurs éventuelles conditions de conservation.
