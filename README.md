# Fonote

## Navigation et recherche

Trois cartes : **Mes matchs annotés** à gauche, **Accueil** au centre et **Calendrier** à droite.
Depuis l’accueil, glisser vers la droite ouvre les matchs annotés ; vers la gauche, le calendrier.
Les matchs annotés regroupent les rencontres avec au moins une note non supprimée, même terminées,
y compris les démonstrations et les notes synchronisées. La recherche filtre équipes, compétition,
date et texte des notes, sans distinction de casse ou d’accents. Un match dont les détails manquent
reste accessible par son identifiant. Le calendrier dispose d’une recherche dans la semaine affichée,
conservée lorsque l’on change de semaine. Les champs restent visibles au-dessus des listes.
Les favoris de l’accueil montrent uniquement les matchs programmés, en cours ou à la pause ;
les étoiles des matchs terminés sont conservées mais leurs cartes sont masquées à l’accueil.

## Accueil personnalisé

L’étoile d’un match dans le calendrier l’ajoute à **Mes matchs favoris** sur l’accueil ;
un second appui le retire. Les favoris sont conservés sur cet appareil. Ajouter un favori
lance aussi le téléchargement de son détail si le serveur répond, pour préparer les notes hors ligne.
**Prochains matchs de mes clubs** affiche la rencontre à venir la plus proche pour chaque club choisi
dans **Mes suivis**, sans doublonner une rencontre entre deux clubs suivis. Les matchs terminés,
annulés, reportés ou déjà en cours sont exclus de cette section ; **Aujourd’hui** reste disponible.

Le serveur expose `/v1/football/teams/{id}/matches`, vers le
[calendrier d’un club de football-data.org](https://docs.football-data.org/general/v4/team.html#_matches).
Le client demande l’année à venir (100 rencontres maximum par club), puis choisit le prochain match
par date. Les réponses rejoignent les calendriers enregistrés pour l’usage hors ligne. Un club sans
rencontre future connue est signalé, et les chargements réussis ne sont pas répétés avant cinq minutes
au cours d’une même session. Redémarrer le serveur après cette mise à jour pour activer la nouvelle route.

## Utilisation hors ligne

L’application conserve automatiquement les calendriers consultés, les détails des matchs ouverts
(compositions, faits et statistiques), le catalogue des compétitions consulté et les écussons affichés.
L’accueil propose **Matchs enregistrés**, y compris en mode démo : cette liste reste accessible après
fermeture de l’application, sans serveur, sans filtre de suivis ni limite de date. Un match connu
seulement par son calendrier permet déjà de prendre des notes générales ; sa composition nécessite
un premier téléchargement en ligne.

Les copies locales s’affichent immédiatement. Hors mode démo, les calendriers sont actualisés en
arrière-plan si le serveur répond ; les matchs ouverts le sont également, sans remplacer une note
en cours. Les données peuvent rester anciennes hors ligne. Une réponse sans composition ne supprime
pas une composition déjà enregistrée. Les matchs d’une même période sont fusionnés par identifiant,
et chaque détail est remplacé à son actualisation, sans historique des réponses. Les matchs enregistrés
ne sont pas purgés automatiquement. Les notes restent dans leur journal SQLite indépendant ; la
migration conserve les notes existantes et leur état de synchronisation. La synchronisation des notes
avec les autres appareils reste manuelle et nécessite le serveur et son jeton.

Vérification sur émulateur démarré : `bash scripts/check-offline-android.sh` (sans dépendance de test à télécharger).
Les contrôles utilisent une base de test séparée : migration depuis la version 1, conservation des
notes, réouverture, calendriers chevauchants, conservation des compositions et transactions invalides.

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

Le trigramme du club et son écusson voyagent avec la composition, dans `abbreviation` et `logo`
de chaque équipe. Ils viennent d’ESPN et se posent à côté des `tla` et `crest` de football-data,
jamais par-dessus : les deux fournisseurs ne disent pas la même chose — `TRY` contre `ETR` pour
Troyes, et un `RC ` complété d’une espace pour Strasbourg — et c’est au client de choisir. Il
préfère ESPN, dont les trois lettres se lisent mieux, et retombe sur football-data pour une
compétition qu’ESPN ne couvre pas. Des deux écussons, seul celui d’ESPN est toujours une image
matricielle ; football-data sert la moitié des siens en SVG, que `BitmapFactory` ne lit pas.
L’écusson choisi est celui qu’ESPN dessine pour un fond sombre quand il en publie un — les deux
fichiers sont souvent identiques, mais la note se dessine sur une pelouse de nuit. Le client conserve les écussons téléchargés sur disque, à la taille d’affichage, pour les retrouver hors ligne.

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
- **Huit thèmes au choix dans Accueil → Options**, le thème d'origine compris. Un thème n'est pas qu'une palette : il porte aussi ses rayons de coin (carte, contrôle, action ronde), la façon dont un bouton ordinaire est dessiné — plein, plein sous un filet, ou filet seul —, la présence d'un bord sur les cartes, et la graisse, la casse et l'interlettrage des titres ; deux thèmes qui ne différeraient que par la teinte se liraient comme la même application de mauvaise humeur. Chaque vignette du sélecteur est dessinée dans le thème qu'elle propose, avec ses propres couleurs, ses coins et son style de bouton. Le choix est conservé d'une session à l'autre ; les boîtes de dialogue suivent le thème, clair ou sombre, et les barres système passent leurs icônes en encre sur un thème clair.
  - Cinq thèmes **à cartes**, dans la famille des outils : « Terrain » (vert de pelouse, accent citron, l'origine), « Minuit » (ardoise, lavande en dégradé, coins larges et cartes bordées d'un trait fin), « Papier » (fond clair, cartes blanches, bleu franc), « Stade » (noir de régie, néon menthe, angles vifs, boutons au trait et titres en capitales espacées) et « Argile » (papier chaud, olive, tout en pastilles rondes).
  - Trois thèmes **à plat** (`Skin.flat`), dans la famille des applications sociales : « Fil », « Vert » et « Bleu ». Leur trait commun n'est pas une couleur, c'est une surface en moins — pas de cartes du tout : les lignes sont posées à même la page et un filet les sépare, les icônes de la barre perdent leur pastille, et la pelouse devient le seul bloc coloré de l'écran. « Fil » est monochrome sur un gris neutre, « Vert » un bleu-nuit qui ne sort son vert que sur trois éléments, « Bleu » un seul bleu avec des contrôles entièrement en pilule.
  - Un thème peut **signer d'un dégradé**, mais à un seul endroit : l'anneau du joueur qu'on est en train de noter (`Skin.ring` / `ringEnd`), dessiné en balayage autour de la pastille plutôt qu'en trait — un trait ne porte qu'une couleur. « Fil » y met son orange-magenta ; c'est la seule chose de l'écran qui n'est ni un contrôle ni une surface, donc la seule qui peut se le permettre.
- **Ce qu'un thème n'habille pas** : la pelouse reste verte et les maillots gardent les couleurs des clubs, parce que ce sont les données du match et non un décor. Ce qui traverse cette frontière est ajusté plutôt que remplacé (`Skin.readable`) : la couleur d'un club est éclaircie ou assombrie juste assez pour se lire sur la page du moment — un jaune moutarde disparaît sur du papier blanc —, et l'accent d'un thème clair, sombre par construction puisqu'il doit porter sur une page pâle, est éclairci avant d'aller sur le terrain. `Skin` est du Java sans Android, donc mesurable hors appareil : `SkinCheck` vérifie les contrastes WCAG 2.1 de chaque thème (encres à 7:1, tout ce qui porte du sens à 4,5:1), sur la page, sur les cartes, sur les pastilles et sur la plaque des noms du terrain. Un thème ajouté qui ne tient pas ces rapports échoue là plutôt que sur l'écran d'un lecteur.
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
    (les boutons « + équipe » les placent à leur poste réel, y compris un remplaçant à la place qu'il occupe à
    cette minute, et la suite du geste est une correction plutôt qu'un placement à partir de rien),
    et **terrain complet**, les 22 dans leur formation, qu'on déplace. On passe de l'un à l'autre à
    tout moment ; vider le terrain se rattrape par `↶`. Un pion sans nom — « Pion — Strasbourg » — sert pour l'adversaire dont le seul rôle est d'avoir été éliminé.
  - Sans outil sélectionné, glisser déplace les joueurs ; les quatre
    tracés dessinent. Un doigt qui glisse est ambigu — « il était plus à gauche » ou « le ballon est
    parti là-bas » — et deviner faux coûte soit un tracé perdu, soit un joueur déplacé qui était
    bien placé. Le dire coûte un tap avant une série de tracés et ne coûte jamais une erreur.
  - **Déplacement par lots.** Sans outil sélectionné, glisser sur la pelouse **vide** encadre : tous les
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
    Quatre des cinq marques de la palette (passe, course, tir, gomme) sont des `VectorDrawable`
    du projet, dessinés comme le tableau dessine : le trait plein et sa pointe, les pointillés,
    les deux rails. La cinquième est le ⚽ du système, gardé parce qu'aucun ballon tracé d'un
    seul trait ne fait un ballon — un pentagone dans un cercle se lit comme une cible, avec ses
    coutures comme une roue. Toutes tiennent dans le même carré de 20 dp, quelle que soit leur
    matière : la marque est posée là comme une image et non dans la ligne de texte, sinon les
    cinq cases ne s'alignaient pas — un glyphe pend à une ligne de base et laisse dessous le
    jambage d'une lettre que personne n'a écrite, et un emoji remplit son cadratin quand une
    flèche y laisse de l'air. Le ⚽ est réglé sur l'encre de ses voisines, pas sur la boîte, sans
    quoi il écrase la rangée. Le couple marque + nom est posé deux dp sous le milieu de la case :
    exactement centré, il paraît haut, le nom réservant sous sa ligne de base la place d'un
    jambage qu'aucun des cinq mots n'a — l'encre s'arrête avant le bas, la boîte non, et c'est
    l'encre qu'on lit.
    Le trait suit le doigt : un glissé droit donne une droite, un glissé courbe garde sa courbe.
    Le tremblement du doigt, lui, ne survit pas : chaque point relevé entre les deux bouts est
    ramené deux fois à mi-chemin du milieu de ses voisins, ce qui efface exactement ce qui alterne
    d'un échantillon au suivant — la définition d'un tremblement. Le déplacement est plafonné à un
    centième de terrain, donc un angle voulu est arrondi et non coupé, et les deux bouts ne bougent
    jamais : ce sont les joueurs auxquels le tracé s'est aimanté. Le lissage se fait au tracé
    (`Track.eased`) et non à l'enregistrement, si bien que le ballon et le joueur suivent la ligne
    même que le tableau dessine, et que les notes prises avant se relisent comme celles d'après.
  - Le départ d'un tracé **s'aimante** au joueur qui se trouve à côté, et l'arrivée aussi quand
    c'est le ballon qui voyage : « de Ripart vers Yassine » est ainsi exact sans viser au pixel,
    et la passe part bien des pieds de Yassine à l'instant où il la reçoit. **L'arrivée d'une
    course, elle, ne s'aimante pas** : elle se pose là où le doigt s'est levé. Un joueur qui court
    le long d'un partenaire ne lui est pas rentré dedans, et le déposer pile sur lui met deux
    maillots sur le même brin d'herbe — un placement que personne n'a demandé. Le trait est ensuite
    reculé du disque qu'il touche, sinon la pointe de flèche disparaît sous le joueur qu'elle
    désigne. Un tracé garde les coordonnées avec lesquelles il a été dessiné : un schéma enregistre
    où le ballon est allé à un instant, pas un lien qui suivrait un joueur.
  - Les deux boutons qui ajoutent un joueur portent l’écusson du club et son trigramme — « PSG »,
    « MON » — là où son nom n’a jamais tenu : un quart de cette rangée fait une soixantaine de
    dp, et « Paris Saint-Germain » s’y termine en « Paris Sain… ». Le nom complet reste dit à qui
    écoute l’écran. L’écusson rejoint les trois lettres quand il arrive : une note s’ouvre bien
    avant qu’une image se charge, et le bouton n’attend pas après elle pour être utilisable.
  - Les pastilles du tableau tactique sont nettement plus petites que celles du terrain : 22 dp au
    lieu de 32. Un joueur mesure un mètre sur une pelouse de 68, soit cinq dp — une pastille à
    l'échelle serait un point, et elle a un numéro à porter. Vingt-deux dp valent encore trois
    fois un homme, mais quatre joueurs dans un coin restent quatre joueurs et une passe entre
    voisins reste une passe, ce que trente-deux — six bons mètres de gazon — ne permettait pas.
    Le ballon et son décalage suivent la même réduction ; le terrain de l'écran match, lui, ne
    change pas : on y désigne un joueur du doigt, on n'y dessine pas. Les rayons de préhension
    (26 dp) et d'aimantation (34 dp) sont indépendants du dessin et restent à la taille d'un
    doigt.
  - **↶**, dans la barre de la note, annule le dernier geste : un tracé raté, un joueur déplacé
    par erreur, une gomme qui a pris le joueur au lieu de la ligne. Le journal enregistre bien
    chaque état — c'est ce que le ↶ de l'écran match remonte — mais pas pendant qu'une note est
    encore ouverte, et il faudrait quitter la note pour annuler un trait qu'on vient d'y faire.
    L'historique garde donc les schémas entiers, quarante pas au plus : un schéma est borné par
    construction (trente pions, quarante tracés, cent vingt clés), un pas coûte quelques kilo-octets,
    et rien ne peut se désynchroniser du tableau comme le ferait une opération inverse écrite à la
    main. Le pas est pris **après** le changement, puisque tout changement passe par le même
    point : l'état d'avant est simplement celui du passage précédent. Un changement qui laisse le
    schéma identique n'est pas un pas, sinon il faudrait appuyer deux fois. Chaque note s'ouvre
    sur son propre historique, et le bouton s'éteint quand il n'y a plus rien à reprendre. Le
    retour en arrière s'écrit dans le journal comme n'importe quel changement : là aussi,
    l'annulation écrit l'opération qui compense.
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
- Une note qui ne porte qu'un schéma se résume par **la dernière action de la séquence** —
  « 17 Vitinha passe à 29 P. Brunner », « Frappe de 9 Mbappé », « Conduite de 10 Golovin »,
  « Course de 2 Hakimi » — au lieu du mot « Schéma », qui nommait la chose sans rien en dire : on
  relit une liste de moments pour en retrouver un, et tous les moments dessinés se ressemblaient.
  La dernière, parce que trois bonnes passes et une frappe se retiennent comme la frappe, et
  qu'un schéma est dessiné vers son dernier geste quoi qu'il raconte avant. Tout se pèse sur
  l'horloge que la séquence tient déjà, donc une course faite après la passe a le dernier mot
  aussi bien que le ballon ; à instant égal, c'est le ballon qui nomme, puisque c'est lui que
  l'œil suit. Jamais la séquence entière : une ligne dans une liste n'est pas une séquence, et la
  note est à un doigt de là. La conduite se lit sur le ballon, exactement comme le tableau la lit
  pour choisir entre un trait pointillé et un trait ondulé ; les vieux schémas, dont les tracés
  n'appartiennent à personne, se nomment par leur forme seule (« Passe », « Tir »), et un tableau
  où l'on n'a fait que poser des joueurs annonce leur nombre.
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

Le jeton est un secret : ne pas le committer. La version release refuse HTTP en clair. Un déploiement distant doit passer par HTTPS : le serveur reste ce `http.server`, mais derrière le Nginx du VPS, comme le décrit la section suivante. Un seul espace personnel par serveur, pas de gestion de comptes.

## Déployer le serveur sur un VPS

Le serveur tient dans une image Docker et un `docker compose up -d`. L'image ne contient que
Python, les deux modules de `backend/` et la composition de démonstration qu'ils lisent : le
serveur n'ayant aucune dépendance, il n'y a rien à installer et rien à verrouiller.

- `backend/Dockerfile` — l'image, construite depuis la racine du dépôt : `server.py` cherche
  `android/app/src/main/assets/match.json` à côté de son propre dossier, donc le contexte doit
  voir les deux. Le `.dockerignore` est écrit en liste blanche pour cette raison : il n'y entre
  que ces trois fichiers, ni le `.env` de la machine de build ni les seize gigaoctets de `.tooling/`.
- `compose.yml` — le service, le port et le volume.
- `.env.example` — les variables à recopier dans `.env`.

Copier `.env.example` vers `.env` et renseigner le jeton :

```env
FONOTE_TOKEN=le-meme-jeton-que-dans-l-application
FOOTBALL_DATA_TOKEN=votre-cle-football-data
FONOTE_BACKEND_IMAGE=registry.mmyumu.fr/fonote-backend:1.0.0
```

Sans `FONOTE_TOKEN`, `docker compose` refuse de démarrer plutôt que de lancer un serveur qui
répondrait 401 à tout. Sans `FOOTBALL_DATA_TOKEN`, le serveur démarre et l'application reste en
démo hors ligne, comme en local. Le `.env` de la racine est celui que lit déjà le serveur lancé
à la main : les deux usages partagent le même fichier, ignoré par Git.

Depuis la racine, sur la machine de développement :

```bash
docker compose build
docker compose push
```

Puis sur le VPS :

```bash
docker compose pull
docker compose up -d
```

Le journal des opérations vit dans le volume Docker `fonote_backend_data`, monté sur `/data` :
le conteneur est jetable, la base ne l'est pas. La sauvegarde reste celle décrite plus bas — il
faut passer par l'API SQLite de sauvegarde, pas par une copie du fichier principal :

```bash
docker compose exec backend python3 -c "import sqlite3; s=sqlite3.connect('/data/fonote.sqlite3'); d=sqlite3.connect('/data/fonote-backup.sqlite3'); s.backup(d); d.close(); s.close()"
```

Le conteneur n'écoute que sur `127.0.0.1:8080` du VPS, jamais sur l'extérieur, et tourne sous un
utilisateur sans privilèges. C'est au Nginx de la machine, hors de ce dépôt, de terminer le TLS
et de relayer vers ce port : la version release de l'application refuse le HTTP en clair, et le
jeton ne doit pas traverser un réseau sans chiffrement. `/v1/health` est la seule route ouverte
sans jeton — Docker s'en sert pour le `HEALTHCHECK` —, avec le proxy football, qui n'expose que
les données publiques du fournisseur et jamais la clé.

## Construire Android

Sur cette machine WSL, les outils sont installés dans `.tooling/` (ignoré par Git) : JDK 17, Gradle 8.9, SDK 35, Build-Tools 34, adb, Android Studio et émulateur. Depuis la racine :

```bash
bash scripts/build-android.sh
bash scripts/android-studio.sh
```

Le premier script compile et lance Lint ; le second ouvre Android Studio sur le projet. Le Wrapper Gradle est fourni avec contrôle SHA-256. Sur une autre machine avec Java 17 et le SDK configurés, utiliser `cd android && ./gradlew :app:assembleDebug` (ou `gradlew.bat` sous Windows).

APK : `android/app/build/outputs/apk/debug/app-debug.apk`. L'application cible actuellement API 34 pour ce prototype et fonctionne à partir d'Android 8 (API 26) ; publication Play Store hors périmètre.

Le mode **Démo hors ligne** est activé par défaut dans les Options. L'accueil propose alors deux
matchs locaux : PSG–Monaco (terminé, 1–2, 4 septembre 2026) et Strasbourg–Monaco (à venir, 12 septembre
2026 à 17 h 15). Aucun serveur ni réseau n'est nécessaire pour les ouvrir et y prendre des notes.
Décochez ce mode dans Options pour utiliser un serveur personnel configuré.

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
javac -d /tmp/fonote-checks android/app/src/main/java/fr/fonote/{Formation,Lineup,MatchClock,PlayerName,Skin}.java android/checks/fr/fonote/{Formation,Lineup,MatchClock,PlayerName,Skin}Check.java
for check in Formation Lineup MatchClock PlayerName Skin; do java -ea -cp /tmp/fonote-checks fr.fonote.${check}Check; done
```

La logique sans Android (placement, chronomètre, noms, thèmes) tient dans des classes à part,
vérifiées par ces `assert` sans émulateur ni dépendance de test.

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
  parmi `pass`, `run`, `carry`, `shot` et 2 à 32 couples de coordonnées. `carry` n'est plus écrit —
  une course du porteur du ballon est une conduite, déduite du ballon — mais le journal étant
  immuable, il reste accepté en lecture. Les coordonnées sont des
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

## Séquences tactiques et positions clés

Dans une note tactique, la barre indique le temps écoulé **dans l’action**, indépendamment de
la minute du match. Toucher le temps permet de saisir un instant précis. Chaque joueur possède
ses propres positions clés ; les repères dans la barre indiquent celles du joueur sélectionné
(ou du ballon avec l’outil Ballon). Un toucher sur un repère rejoint sa position ; un appui long
le supprime. Déplacer le doigt annule la suppression — et reprend le déplacement du temps, plutôt
que d’immobiliser le doigt jusqu’à ce qu’il se lève.

La barre est peinte par le client, pas habillée : un rectangle de la largeur de la rangée, rempli
jusqu’au temps courant, les positions clés dressées dedans, et le temps courant en un rectangle
plus haut que la barre et plus clair qu’un repère. Le curseur de la plateforme n’en garde que
l’arithmétique. Il apportait deux choses dont la rangée ne voulait pas : la pastille ronde d’un
écran de réglages, et une piste posée là où le remplissage de son dessin la mettait — dix dp
au-dessus des boutons qui l’encadrent, sans moyen de l’aligner sur eux.

- Chaque équipe a son bouton **+**, qui propose uniquement ses joueurs présents à la minute de
  la note et pas encore placés, par numéro croissant, puis un pion générique de cette équipe.
  Le pion sans équipe n’est plus proposé.
- **Sans outil sélectionné**, le terrain sert à sélectionner et repositionner les joueurs, sans
  créer de clé ni de flèche. Retoucher l’outil actif le désélectionne.
  Si un joueur possède déjà une trajectoire, son placement et toute sa trajectoire sont décalés
  ensemble, en conservant les temps. Cela fonctionne aussi pour une sélection de plusieurs joueurs.
  Pour le faire attendre avant un appel, fixer une position au début de l’appel avec **◆ Positions**,
  puis tracer sa **Course**.
- **↝** donne la durée du prochain trajet, et vaut **auto** par défaut : la durée se déduit alors
  de la longueur du trait, à l’allure de ce qu’il représente — une passe à 15 m/s, un tir à 25,
  un joueur qui court à 6,5. Un terrain fait 105 mètres sur 68, donc une fraction du tableau vaut
  plus dans la longueur que dans la largeur, et les deux axes sont pesés séparément avant que la
  distance ne devienne un temps. La longueur mesurée est celle qui sera parcourue — la polyligne
  lissée, pas la corde entre les deux bouts — si bien qu’une course qui contourne un adversaire
  prend le temps qu’elle fait. Rien n’est chronométré dans une action qu’on note de mémoire, mais
  un ballon long est plus lent qu’un une-deux et une course de trente mètres n’est pas celle de
  deux : le trait le dit déjà. Les durées fixes (0,5 à 10 s) restent là pour la séquence qui doit
  être exacte, et le choix est retenu d’une note à l’autre.
- **Course** enregistre un trajet depuis un joueur, entre le curseur et la fin de la durée choisie
  avec **↝**. Le curseur avance à l’arrivée. Revenir en arrière permet de tracer l’appel simultané
  d’un autre joueur. Un trajet contenant déjà des clés intermédiaires est refusé pour éviter de les
  écraser.
- Il n’y a pas d’outil **Conduite** : une course est dessinée pointillée, et ondulée si le joueur a
  le ballon dans les pieds pendant tout le trajet. Le ballon suit déjà son porteur, donc c’est lui
  qui décide, pas l’outil choisi. Un ballon frappé au départ du trajet est déjà parti — c’est une
  course ; frappé à l’arrivée, il a été conduit jusque-là.
- **Ballon** permet de donner le ballon à un joueur, ou de le placer librement sur le terrain :
  c’est ainsi qu’on met le ballon dans les pieds d’un joueur avant de lui tracer une conduite.
  Un ballon tenu se dessine du côté où son porteur va le jouer, pas toujours à sa droite : un
  décalage fixe ne dit rien, et il le dit même quand le jeu part à gauche. Jamais sous lui, en
  revanche — c’est là qu’est écrit son nom, et un nom à moitié couvert par un ballon est pire
  qu’un ballon du mauvais côté : une passe vers le bas pose donc le ballon à côté du joueur, du
  côté où elle part. Le ballon d’un joueur qui le conduit, ou qui le garde jusqu’à la fin, se
  pose au-dessus de lui — le seul côté que la plaque du nom laisse libre. Un ballon que personne
  ne tient reste où il est.
  Une **Passe** vers un joueur vise sa position à la réception et lui donne ensuite le ballon.
  Un **Tir** termine sur un point libre.
- **▶** lit la séquence, et le temps se lit à côté — toucher ce temps permet de saisir un instant
  précis. Il est écrit dans la lettre des boutons de la rangée, demi-grasse en 13, et non dans
  celle qu’un `TextView` nu prend par défaut : la romaine du système en 14 mettait deux polices
  dans la même rangée. Comme il répond au doigt, il répond aussi à l’appui, la règle de toutes
  les surfaces cliquables ici. **◆ Positions** permet de retrouver, ajouter, supprimer ou changer le
  temps d’une clé. La gomme retire un joueur, une passe ou la position d’arrivée du trajet touché.

Les temps sont enregistrés en dixièmes de seconde, jusqu’à 120 secondes, avec au plus 120 clés
par piste. Ils décrivent la séquence saisie ; les durées proposées pour dessiner ne sont pas des
mesures automatiques du match. Ces trajectoires préparent une future heatmap des actions notées ;
la heatmap elle-même n’est pas encore calculée.

Le schéma `version: 2` conserve `board`, `tokens`, `shapes` (anciens tracés statiques), et ajoute
`ball`. Chaque pion reçoit un `id` stable dans le schéma et une liste `keys`. Chaque clé contient
`t` (dixièmes de seconde), `x`, `y`, et éventuellement `path` (trajet arrivant à cette clé). Seules
les clés du ballon portent `kind`, ainsi que `owner` (identifiant du pion) et `flight` (trajet vers
la clé suivante, sinon position libre ou suivi du porteur) ; un `kind` laissé par un ancien client
sur la piste d’un joueur est ignoré à la lecture. Les pistes sont triées et leurs temps
uniques. Les anciens schémas sans version restent acceptés et sont convertis lors de l’édition.
Le serveur mis à jour accepte ces schémas et des opérations jusqu’à 4 Mio ; il doit être mis à jour
avant de synchroniser les nouvelles séquences. Un ancien client ne sait pas conserver ces pistes
lors d’une réédition : mettre les clients à jour ensemble.

Vérification des pistes et de leur sérialisation (avec une bibliothèque `org.json` disponible) :

```bash
source scripts/android-env.sh
FONOTE_JSON_JAR="$FONOTE_ROOT/.tooling/android-studio/plugins/grazie/lib/org.json-json.jar"
javac -cp "$FONOTE_JSON_JAR" -d /tmp/fonote-checks android/app/src/main/java/fr/fonote/{Track,Diagram}.java android/checks/fr/fonote/{Track,Diagram}Check.java
java -ea -cp "/tmp/fonote-checks:$FONOTE_JSON_JAR" fr.fonote.TrackCheck
java -ea -cp "/tmp/fonote-checks:$FONOTE_JSON_JAR" fr.fonote.DiagramCheck json
```
