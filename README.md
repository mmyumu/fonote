# Fonote

## Navigation et recherche

Trois cartes : **Mes matchs annotés** à gauche, **Accueil** au centre et **Matchs enregistrés** à
droite ; depuis l’accueil, glisser vers la droite ouvre les matchs annotés, vers la gauche les matchs
enregistrés. Le **Calendrier** est un écran à part, ouvert par les liens
« Calendrier › » de l’accueil : c’est une destination que l’on demande, pas une carte que l’on
croise. Une fois dedans, glisser change de semaine — vers la gauche la suivante, vers la droite la
précédente —, comme les deux flèches de l’en-tête, qui font exactement le même mouvement. Une semaine
va toujours du lundi au dimanche : les dates de l’en-tête sont celles du calendrier, pas une fenêtre
de sept jours autour du jour d’ouverture. La semaine
voisine est déjà dessinée avant d’arriver : les trois semaines affichées sont demandées ensemble.
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

Le serveur expose `/v1/football/teams/{id}/matches`. ESPN ne publie dans le calendrier propre
d’un club que les rencontres déjà jouées ; le serveur lit donc le calendrier du championnat où
ce club évolue — résolu une fois pour la saison — puis n’en garde que ses rencontres, en y
ajoutant ses soirées européennes. Le client demande l’année à venir (100 rencontres maximum par
club), puis choisit le prochain match par date. Les réponses rejoignent les calendriers enregistrés pour l’usage hors ligne. Un club sans
rencontre future connue est signalé, et les chargements réussis ne sont pas répétés avant cinq minutes
au cours d’une même session. Redémarrer le serveur après cette mise à jour pour activer la nouvelle route.

## Utilisation hors ligne

L’application conserve automatiquement les calendriers consultés, les détails des matchs ouverts
(compositions, faits et statistiques), le catalogue des compétitions consulté et les écussons affichés.
La carte **Matchs enregistrés**, à droite de l’accueil, existe aussi en mode démo : cette liste reste accessible après
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

## Données football : ESPN (usage personnel)

Tout vient du flux public d’ESPN, sans clé ni compte : le catalogue des compétitions, les
calendriers, le calendrier d’un club et la fiche d’un match. Le contrat `/v1/football/…` n’a pas
changé de forme — c’est celle que le client enregistre sur l’appareil et relit hors ligne — mais
les identifiants qu’il transporte sont ceux d’ESPN.

Il n’y a plus de rapprochement entre deux fournisseurs. Auparavant le calendrier venait d’un
fournisseur et la composition d’un autre, sans identifiant commun : les deux fiches étaient
raccordées en comparant des noms d’affichage et un horaire, ce qui échouait en silence dès qu’un
club s’écrivait autrement d’un côté que de l’autre. La fiche d’un match est maintenant lue
directement à son identifiant, sur `summary`, qui répond quel que soit le championnat cité —
un match enregistré s’ouvre donc sans rien avoir à retrouver d’abord. Les 22 titulaires doivent
être présents et distincts, sans quoi la composition est déclarée indisponible et le match reste
ouvrable pour des notes générales.

Les couleurs des maillots viennent d’ESPN : elles sont éclaircies pour que le numéro reste
lisible, et l’équipe visiteuse bascule sur sa couleur alternative si les deux se ressemblent
trop. Un cache mémoire limite les appels : cinq minutes minimum pour l’accueil, les calendriers et les clubs suivis, même aujourd’hui.
Seul le détail d’un match ouvert en direct peut être relu après trente secondes. Le
catalogue et la liste des clubs d’un championnat tiennent une journée. Un calendrier interroge
les treize compétitions, quatre à la fois : demandés l’un après l’autre ils feraient attendre le
client sur une page qu’il a déjà dessinée de sa copie locale, tous d’un coup ils arriveraient en
rafale sur un flux dont on est l’invité. Un championnat qui ne répond pas se tait sans faire
échouer les autres. ESPN déclare lui-même son flux périmé au bout de neuf secondes, ce rythme
reste donc très en deçà de ce que son propre cache anticipe.

Sur l’accueil et le calendrier, tirer vers le bas depuis le haut de la liste puis relâcher
actualise les données. Un indicateur de chargement apparaît centré au-dessus du contenu, puis
se replie progressivement à la fin du chargement. Pendant une actualisation, on peut encore
tirer légèrement le contenu : il revient doucement au relâchement, sans nouvelle requête.
L’accueil relit aussi les matchs favoris et les prochains matchs des clubs suivis.

Le geste relit le serveur sans forcer ESPN : les durées de cache ci-dessus restent applicables,
et les requêtes simultanées vers une même ressource partagent une seule lecture ESPN.
En cas d’échec du fournisseur, sa dernière réponse en cache reste disponible et une nouvelle
tentative attend cinq minutes, même si aucune réponse n’avait encore été obtenue. Sans connexion,
les données déjà enregistrées sur le téléphone restent consultables.

Le serveur rapporte aussi le déroulé du match — remplacements, buts, cartons, coup d’envoi et
mi-temps réels — dans `timeline` et `clock`, les remplaçants dans `bench`, les compteurs
d’équipe dans `team_stats`, le stade, l’arbitre et l’affluence dans `ground`, et les compteurs
par joueur dans le `stats` de chaque ligne de composition. Le serveur transmet les clés brutes
du fournisseur (`possessionPct`, `wonCorners`…) : les nommer en français est l’affaire du client,
comme pour les codes de compétition. Rien de tout cela n’entre dans le journal d’opérations :
les faits du fournisseur s’affichent à côté des notes, jamais dedans, pour que le bilan continue
de ne mesurer que ce qui a été relevé.

Le trigramme du club et son écusson voyagent avec la composition, dans `abbreviation` et `logo`
de chaque équipe, à côté des `tla` et `crest` que porte déjà toute entrée de calendrier. Depuis
qu’il n’y a plus qu’un fournisseur les deux disent la même chose ; le client lit les deux pour
qu’un club nommé depuis l’une ou l’autre source tienne dans la même pastille. L’écusson retenu
est celui qu’ESPN dessine pour un fond sombre quand il en publie un — les deux fichiers sont
souvent identiques, mais la note se dessine sur une pelouse de nuit. Un écusson qui n’est pas une
image matricielle est écarté plutôt que dessiné en carré vide, `BitmapFactory` ne lisant pas le
SVG. Le client conserve les écussons téléchargés sur disque, à la taille d’affichage, pour les
retrouver hors ligne.

Une carte de match porte une petite icône de terrain quand sa composition est déjà publiée :
c’est une invitation à ouvrir la rencontre maintenant plutôt qu’après le coup de sifflet. Le
calendrier ne la demande qu’avec `lineups=1`, et le serveur ne va vérifier que les matchs dont
la réponse est à la fois connaissable et utile — coup d’envoi dans les deux heures, en cours, ou
terminés depuis moins de quatre. Le reste repart en `lineup_status: "unknown"`, jamais en une
valeur qu’on n’a pas vérifiée : l’icône n’est dessinée que sur ce qui a été regardé et trouvé,
et son absence ne promet rien. Une soirée européenne coûte ainsi quelques lectures au lieu d’une
par rencontre de la saison — et ces lectures ne sont pas perdues, puisque ouvrir le match trouve
ensuite la feuille déjà en main. L’appareil retient ce qu’il a vu publié : un calendrier qui
répond `unknown` ne fait pas disparaître un badge déjà acquis.

Deux choses se perdent avec l’ancien fournisseur. ESPN ne publie pas de numéro de journée : une
carte de championnat affiche « Championnat » là où elle affichait « Championnat · J3 ». Et les
identifiants ayant changé d’espace, les suivis, les favoris et les matchs déjà enregistrés sur
un appareil se rapportent à des numéros que ce flux ne connaît pas : ils sont à recocher une
fois. Les notes écrites avant la bascule portent un identifiant de match en `fd-…` ; le journal
étant immuable, le serveur les accepte toujours et le client les montre comme des matchs dont
le détail n’est pas téléchargé, sans jamais les confondre avec un match d’ESPN.

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

- Accueil, calendrier, suivis et options suivent les conventions Android : titre et actions dans une barre en haut (retour à gauche, ☆ suivis et ⚙ options à droite) au lieu de gros boutons dans le contenu, retour tactile sur chaque surface cliquable, barres système à la couleur de la page. Un match se lit comme un match : heure locale (plus d'UTC) ou état en cours à gauche, les deux équipes et leur score au centre, la compétition en dessous. Le calendrier groupe ses rencontres par jour ; une liste vide propose d'aller choisir ses suivis. Son en-tête — titre, semaine et recherche — ne bouge pas : seules les semaines glissent sous lui, et la semaine arrivée reprend sa place au milieu sans que rien ne paraisse bouger. Les codes du fournisseur sont traduits (« REGULAR_SEASON » devient « Championnat · J3 »). Les icônes (roue, étoile, flèches) sont des `VectorDrawable` du projet, dans `android/app/src/main/res/drawable/` : les `android.R.drawable.ic_menu_*` de la plateforme sont des images matricielles d'avant Material qui changent d'un constructeur à l'autre, et un glyphe de police comme ⚙ tombe sur l'emoji du système.
- Terrain avec les 22 titulaires placés à leur poste réel, chaque équipe dans sa formation (4-4-2 / 4-3-3).
- **Le match tient en cinq cartes côte à côte, le terrain au milieu**, et plus en un terrain
  suivi d'une rangée de boutons. Vers la droite se prend ce que le fournisseur raconte — les
  **faits**, puis les **statistiques** ; vers la gauche ce que j'ai écrit — mes **observations**,
  puis le **bilan**. Chaque direction dit ainsi ce qu'elle rapporte, le travail reste au centre,
  et les quatre boutons du bas ont disparu avec ce qu'ils cachaient : les notes et le bilan sont
  devenus des cartes, synchroniser est devenu le geste que toutes les autres pages emploient
  déjà — tirer la page vers le bas —, l'export s'est rangé au pied des observations qu'il
  exporte, et le serveur se configure dans Accueil → Options, où se configure le reste.
  Sous le terrain il ne reste rien : ni rangée de boutons, ni rappel des cartes voisines, ni
  ligne d'état. Le balayage est le seul chemin, et il est le même sur les cinq cartes ; une barre
  qui aurait nommé les voisines coûtait une rangée pleine pour dire ce qu'un doigt découvre en
  une seconde. Toute la hauteur ainsi libérée — deux rangées — va à la pelouse.
  **Annuler** (↶) et **refaire** (↷) se sont rangés en bout de la ligne d'invite du panneau de
  saisie, qui a de la place de reste au repos : la correction du travail se pose près de l'endroit
  où le travail s'écrit, sans coûter une rangée au terrain. Les deux sont toujours là, grisés
  quand ils n'ont rien à faire : un bouton qui apparaît et disparaît déplace son voisin, et le
  doigt qui visait « annuler » tombait sur « refaire ».
- **Faits du match**, la carte à gauche du terrain : on l'amène en balayant le doigt vers
  la droite, on revient en balayant vers la gauche, et le geste retour fait la même chose avant de
  toucher à la note. Elle porte le score, le fil du match (buts avec passeur, cartons,
  remplacements, à la minute et aux couleurs de l'équipe), puis le stade et l'arbitre.
  Le pager est écrit dans le projet (`Pager.java`) : le client ne porte aucune dépendance
  d'interface, et ce qu'un pager ajoute à un défilement horizontal tient en une accroche et la
  notion de page affichée. Une page tourne au cinquième de l'écran parcouru, pas à la moitié.
  Les rangées qui défilent latéralement — la palette d'actions, les joueurs d'une note — prennent
  le geste pour elles en l'interceptant avant leurs propres boutons, sinon ceux-ci consomment
  l'appui et la carte tournerait au lieu de faire défiler la palette ; une rangée qui tient déjà
  entière dans l'écran laisse au contraire passer le geste. Le geste retour ne saute pas au terrain :
  il défait une carte à la fois, et il revient vers le terrain quel que soit le côté d'où l'on
  vient — le travail est au milieu de la pile. Page séparée et annoncée comme telle — « rien ici n'entre
  dans votre journal ni dans votre bilan » — parce que le bilan promet de ne mesurer que ce qui a
  été relevé, et qu'un but compté par le fournisseur n'est pas une observation.
  L'affluence est tue quand elle vaut zéro, ce qui veut dire « inconnue » et non « personne ».
- **Statistiques**, une carte de plus à gauche des faits : un balayage de plus vers la
  droite amène les compteurs des deux équipes en vis-à-vis, sous le nom de chacune et à sa couleur.
  Page à part parce qu'une colonne de chiffres se parcourt du regard quand un fil de match se suit
  ligne à ligne, et titrée « Statistiques » — ce que la carte montre, pas qui le fournit ; la
  provenance tient dans la ligne grise dessous, avec le même rappel que rien de tout cela n'entre
  dans le journal ni dans le bilan. Sur les 28 chiffres publiés, 13 sont montrés : les pourcentages
  dérivés ne font que répéter le couple au-dessus d'eux. Un match dont le fournisseur ne raconte
  rien mais compte quand même n'a pas de carte des faits, et son premier balayage vers la droite
  mène donc droit aux chiffres.
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
- **Les bancs sont au bord du terrain** : les remplaçants de chaque club sur sa ligne de touche — l'équipe
  du haut à gauche depuis son but, celle du bas à droite depuis le sien, le demi-tour que le terrain fait
  déjà faire à l'équipe adverse —, par numéro de maillot, et le **coach** au bout, côté but. Un joueur
  remplacé retourne au banc, grisé. On les touche comme un joueur du terrain : ils entrent dans la note
  avec leur action, et dans le bilan. La pelouse cède sa marge extérieure et un peu de largeur pour leur
  laisser la place ; une composition publiée sans banc garde toute la largeur. ESPN ne publie pas les
  entraîneurs en football, pas même un nom : le coach est « Coach PSV », identifié par son club
  (`espn-coach-<id du club>`), et le serveur accepte cet identifiant dans une note comme un joueur ESPN.
- **Neuf thèmes au choix dans Accueil → Options**, le thème d'origine compris. Un thème n'est pas qu'une palette : il porte aussi ses rayons de coin (carte, contrôle, action ronde), la façon dont un bouton ordinaire est dessiné — plein, plein sous un filet, ou filet seul —, la présence d'un bord sur les cartes, et la graisse, la casse et l'interlettrage des titres ; deux thèmes qui ne différeraient que par la teinte se liraient comme la même application de mauvaise humeur. Chaque vignette du sélecteur est dessinée dans le thème qu'elle propose, avec ses propres couleurs, ses coins et son style de bouton. Le choix est conservé d'une session à l'autre ; les boîtes de dialogue suivent le thème, clair ou sombre, et les barres système passent leurs icônes en encre sur un thème clair.
  - Six thèmes **à cartes**, dans la famille des outils : « Terrain » (vert de pelouse, accent citron, l'origine), « Minuit » (ardoise, lavande en dégradé, coins larges et cartes bordées d'un trait fin), « Papier » (fond clair, cartes blanches, bleu franc), « Stade » (noir de régie, néon menthe, angles vifs, boutons au trait et titres en capitales espacées), « Argile » (papier chaud, olive, tout en pastilles rondes) et « Diagonale », hommage à l'AS Monaco.
  - « Diagonale » est le seul à dessiner sur son fond (`Skin.sash`) : l'écran est coupé d'un coin à l'autre comme le maillot monégasque, de l'épaule droite à la hanche gauche — vue de face, du coin haut gauche au coin bas droit —, la moitié haute voilée de rouge. Une seule diagonale, tenue par la fenêtre derrière des pages transparentes : elle reste immobile quand une liste défile ou qu'une carte glisse, et elle passe sous les barres système. Les cartes, elles, restent blanches ; coupées chacune, une liste de matchs devenait une pile de maillots. Un voile et non le rouge franc, parce que les titres et les listes traversent la coupe : `SkinCheck` exige qu'ils se lisent des deux côtés. Le rouge franc reste au bouton principal et aux liens, et l'anneau du joueur noté passe du rouge au blanc. Le raté y prend l'orange brûlé, pour ne pas se confondre avec l'accent. Et la pelouse y est d'un vert plus clair (`Skin.lawn`) : le vert de nuit des autres thèmes faisait un trou sombre dans une page aussi pâle.
  - Trois thèmes **à plat** (`Skin.flat`), dans la famille des applications sociales : « Fil », « Vert » et « Bleu ». Leur trait commun n'est pas une couleur, c'est une surface en moins — pas de cartes du tout : les lignes sont posées à même la page et un filet les sépare, les icônes de la barre perdent leur pastille, et la pelouse devient le seul bloc coloré de l'écran. « Fil » est monochrome sur un gris neutre, « Vert » un bleu-nuit qui ne sort son vert que sur trois éléments, « Bleu » un seul bleu avec des contrôles entièrement en pilule.
  - Un thème peut **signer d'un dégradé**, mais à un seul endroit : l'anneau du joueur qu'on est en train de noter (`Skin.ring` / `ringEnd`), dessiné en balayage autour de la pastille plutôt qu'en trait — un trait ne porte qu'une couleur. « Fil » y met son orange-magenta ; c'est la seule chose de l'écran qui n'est ni un contrôle ni une surface, donc la seule qui peut se le permettre.
- **Ce qu'un thème n'habille pas** : la pelouse reste verte — un thème clair peut tout au plus l'éclaircir d'un ton, sans que le blanc des lignes cesse de s'y détacher — et les maillots gardent les couleurs des clubs, parce que ce sont les données du match et non un décor. Ce qui traverse cette frontière est ajusté plutôt que remplacé (`Skin.readable`) : la couleur d'un club est éclaircie ou assombrie juste assez pour se lire sur la page du moment — un jaune moutarde disparaît sur du papier blanc —, et l'accent d'un thème clair, sombre par construction puisqu'il doit porter sur une page pâle, est éclairci avant d'aller sur le terrain. `Skin` est du Java sans Android, donc mesurable hors appareil : `SkinCheck` vérifie les contrastes WCAG 2.1 de chaque thème (encres à 7:1, tout ce qui porte du sens à 4,5:1), sur la page, sur les cartes, sur les pastilles et sur la plaque des noms du terrain. Un thème ajouté qui ne tient pas ces rapports échoue là plutôt que sur l'écran d'un lecteur.
- Deux styles de pastille au choix dans **Accueil → Options** : « Verre » (disque sombre, couleur de l'équipe en anneau, en halo et sur le numéro) ou « Plein » (disque peint). Le choix est montré par un aperçu dessiné avec le même code que le terrain, et il est conservé d'une session à l'autre. Le nom de chaque joueur porte sa propre plaque, dessinée sur une couche à part, au-dessus de **toutes** les pastilles : sur une formation à cinq lignes, une demi-pelouse ne peut pas loger six rangées sans chevauchement, donc la question n'est pas de l'éviter mais de choisir qui l'emporte — un nom reste toujours lisible, un numéro à moitié couvert garde sa couleur et sa place. Seul le joueur qu'on est en train de noter passe devant les noms, pour que lire un voisin ne coûte jamais le numéro de celui qu'on note. La cible tactile est la pastille elle-même (44 dp) et non la boîte qui portait le nom : deux voisins sont moins faciles à confondre du doigt.
- Deux gestes pour noter : toucher un joueur, puis son action. Il n'y a pas de bouton d'enregistrement — chaque action écrit la note aussitôt.
- Une note décrit un moment, pas un joueur. Tant qu'une note est ouverte, le panneau **collecte** : chaque joueur touché la rejoint avec son action propre, et « A marque, B délivre la passe décisive, C rate son arrêt » s'enchaîne d'un trait en six taps, sans quitter le terrain. Sans note ouverte, toucher un joueur en démarre une. Le seul geste qui reste à dire tout haut est donc la fin d'un moment : **« Terminé »**, qui rend la liste des notes. La corbeille à côté jette la note en cours ; tant qu'aucune action n'a été choisie il n'y a rien à jeter, et le bouton dit « Abandonner ».
- **Note libre et note de match : une seule note écrite, et la minute seule les distingue.** La note rapide se tape — un joueur, une action, et elle s'écrit aussitôt ; la note écrite prend le temps : on tape le texte qu'on veut, et elle ne s'écrit qu'à « Terminé », puisque son texte est la note et qu'il vient en dernier. « Abandonner », à côté, ferme sans rien écrire : une note neuve ne laisse rien, une note rouverte reste telle qu'elle était. Ce sont deux boutons et non un seul qui change de mot — le premier texte tapé faisait passer l'unique bouton à « Terminé », et plus rien ne permettait de renoncer ; « Terminé » reste éteint tant qu'il n'y a rien à écrire, et une note déjà écrite garde sa corbeille, réduite à l'icône. Les deux boutons du panneau au repos ouvrent le **même panneau** : « + Note libre » à la minute du chrono, « ✎ Note de match » sans minute. Les joueurs, les remplaçants et les coachs qu'on touche y sont **nommés**, jamais crédités — une coche sur leur pastille, rien au bilan —, et les deux écussons sous le terrain en font la note d'un club, l'un ou l'autre, jamais les deux. La minute tient la tête de la ligne ; la toucher la corrige, « Sans minute » en fait une note de match, et une note de match à qui l'on donne une minute redevient une note libre : passer de l'une à l'autre ne réécrit rien d'autre. Seule la note écrite peut se passer de minute : une action se produit à une minute. Les anciennes notes sans joueur — une minute et un texte — se rouvrent dans ce panneau comme des notes libres.
- **Note tactique**, le second mode de prise de note, ouvert par « ▤ Tactique » à côté de
  « + Note libre ». Le mode rapide répond à « qui a fait quoi, et à quelle minute » ; celui-ci
  répond à « où, et vers qui » — une passe mérite d'être dessinée quand ce qui compte est la ligne
  qu'elle a prise et les joueurs qu'elle a éliminés, et aucune palette de dix-huit symboles ne dit
  cela. C'est **la même note** en dessous : même identifiant, même minute, même commentaire, même
  récapitulatif, et les actions données ici comptent dans le bilan exactement comme celles tapées
  sur le terrain. Seule la surface change, et elle prend tout l'écran — un tableau qui partage la
  place avec un panneau est un tableau sur lequel on ne peut pas dessiner.
  - **« Abandonner » et « Terminé », deux boutons**, comme sur la note écrite. Le tableau n'a pas
    de bouton d'enregistrement — chaque trait s'écrit aussitôt —, donc renoncer doit écrire le
    chemin du retour : une note née sur le tableau est supprimée, une note rouverte est remise
    telle qu'elle était à l'ouverture — minute, actions, commentaire et dessin —, et seul ce qui a
    changé est réécrit. « Terminé » reste éteint tant que rien n'est tracé ; une note rouverte garde
    sa corbeille, réduite à l'icône.
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
  porte donc le tableau lui-même, en petit, et la toucher rouvre le tableau.
- Chaque carte de **« Mes observations »** porte ses deux actions en haut à droite, deux icônes
  du même trait : le **crayon** rouvre la note là où elle a été écrite (panneau, ou tableau pour
  une note dessinée), comme un toucher sur la carte ; la **poubelle** demande confirmation.
  L'appui long les cachait à qui ne le connaissait pas. Le résumé des dernières notes sous le terrain ne supprime plus rien : tout
  se corrige ou s'efface depuis la liste.
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
- Le geste retour défait l'écran courant au lieu de quitter : il ramène d'abord au terrain, une carte à la fois et quel que soit le côté d'où l'on vient, puis ferme la note ouverte en la gardant, et ne sort de l'application qu'en dernier recours.
- Retirer un joueur de la note se fait par **appui long sur le terrain**, là où on l'a mis — la croix de sa pastille fait la même chose. Un tap sur un joueur déjà dans la note vise sa ligne pour corriger son action, ce qui interdisait le double-clic. Retirer le dernier joueur d'une note l'efface.
- Une note laissée ouverte pendant que le match avance est le seul risque de la collecte : passé deux minutes, sa pastille de minute vire à l'ambre.
- La provenance de la composition (« 4-1-2-1-2 / 4-2-3-1 · ESPN, placement schématique · Ligue 1 ») ne s'écrit plus du tout sur l'écran du match : elle se lisait une fois et occupait une rangée pour toujours. Les formations se voient sur le terrain, et la compétition est déjà dans le bandeau. Seule l'absence de composition mérite encore une phrase, et un terrain vide a toute la place pour la porter.
- Composition dans un bandeau fixe sous le terrain, jamais en surimpression : le terrain reste visible et cliquable. Sa hauteur ne varie pas, pour qu'ouvrir une note ne déplace jamais les joueurs sous le doigt.
- Dix-huit actions appariées geste par geste, réussi au-dessus, raté en dessous : bon/raté, but/CSC, passe décisive/perte de balle, passe/passe ratée, dribble/dribble raté, tir cadré/tir manqué, geste défensif/duel perdu, arrêt/arrêt raté, jaune, rouge. Chacune porte un poids ; polarité, couleur et bilan en découlent, au lieu d'être choisis à la main.
- Sur chaque carte du bilan, une ligne grise porte ce que le fournisseur a compté pour ce joueur —
  buts, tirs, arrêts, fautes, cartons — à côté de la note et jamais dedans. Seuls les compteurs non
  nuls sont écrits : une ligne de douze zéros ne dit rien. Les compteurs qui décrivent l'équipe et
  non le joueur (buts encaissés, tirs subis, arrêts) ne sont montrés qu'au gardien : ESPN les porte
  sur la ligne de tout le monde, et « 1 encaissé » sur un attaquant se lit comme sa faute. Un
  gardien entré en cours de jeu, que le fournisseur n'appelle que « Substitute », est reconnu à ce
  qu'on lui a compté.
- Bilan par joueur déduit des notes : solde signé sur le maillot au repos, carte dédiée à deux balayages du terrain — après mes observations, puisqu'une note par joueur se lit après les notes qui la font — avec une note sur dix (base 6, une demi-note par point), le détail qui la justifie et le rappel qu'elle ne mesure que ce qui a été relevé.
- Chronomètre automatique depuis le coup d'envoi **réel** lorsque le fournisseur le publie, et non l'horaire
  annoncé : un coup d'envoi retardé de dix minutes décalait sinon toutes les notes du match. La minute de
  la note est préremplie. Ajustable sur la minute de la diffusion, pause mi-temps, coup d’envoi à 0′, reprise à 45′ ; il survit à la fermeture de l'application.
- Historique, commentaires facultatifs, suppression.
- `↶` annule **le dernier geste**, et non la dernière ligne du journal : une suppression est
  défaite, une note complétée revient à sa version précédente, un commentaire au texte qu'il
  portait. Écrire une note de match ou une note libre, c'est écrire la note puis son texte —
  deux lignes pour un seul geste, et défaire le texte laissait derrière une note vide qu'il fallait
  annuler une seconde fois. Les lignes de queue d'une même note se défont donc ensemble, jusqu'à
  l'écriture de la note comprise ; une note qui naît du geste part d'une seule suppression, ce qui
  laisse son texte et son schéma intacts dans le journal — la rétablir la rend telle qu'elle était,
  et non vidée de sa moitié. Jamais deux fois la même sorte d'opération, sinon ce sont deux gestes :
  corriger le texte d'une note écrite plus tôt défait le texte, pas la note, et un tableau dessiné
  trait par trait se défait trait par trait. Rien n'est retiré du journal — l'annulation écrit les
  opérations qui compensent, donc elle se synchronise comme le reste. Toute nouvelle action replace
  le curseur à la fin.
- `↷` refait le dernier geste annulé, sur autant de niveaux qu'on en a défaits, dans l'ordre
  inverse. Refaire n'est pas une opération du journal mais le chemin qu'on vient de parcourir à
  l'envers : il vit en mémoire, pour la session, et se referme dès qu'on écrit quoi que ce soit de
  nouveau. Refaire réécrit les lignes du geste telles quelles ; une note née du geste, défaite
  d'une seule suppression, est simplement rétablie, et son texte revient avec elle puisqu'il
  n'avait jamais quitté le journal. Chaque geste défait retient sa **place** dans le journal, et
  non le nombre de lignes qui le suivent : le journal ne fait que s'allonger, donc une place ne
  bouge pas, alors que ce qui la suit grandit à chaque aller-retour. Refait, le curseur se repose
  sur le geste, si bien qu'annuler le défait encore et qu'un second appui remonte enfin au geste
  d'avant. Une synchronisation renumérote le journal et y glisse les lignes d'autres appareils :
  elle vide le chemin à refaire, dont les places ne désigneraient plus ce qu'elles désignaient.
  Ni l'un ni l'autre ne dit rien : la note disparaît ou revient sous les yeux, et un toast qui le
  répète cache le bas du panneau le temps de le lire.
- SQLite sur Android ; enregistrement hors connexion, conservé après fermeture de l'application.
- **Tirer n'importe quelle carte du match vers le bas actualise le match.** Le geste redemande au
  fournisseur ce qu'il publie de cette rencontre, et passe outre ce qui retient le suivi
  automatique — une fois par minute, et seulement dans la fenêtre où la composition bouge : qui
  tire la page demande maintenant, et « pas encore l'heure » ne se distingue pas d'une panne pour
  qui regarde l'écran. Ne restent que les empêchements réels, chacun dit tel quel : pas d'adresse,
  un match qui ne vient pas du fournisseur, le mode démo, ou une note ouverte — les joueurs ne
  doivent pas bouger sous le doigt.
  La même ligne d'actualisation qu'à l'accueil et au calendrier le montre : elle prend exactement
  la hauteur que le doigt a ouverte, donc la page ne bouge pas d'un pixel au relâchement, puis se
  replie sur ses 56 points le temps de la requête et disparaît. Elle vit au-dessus du pager et non
  dans les cartes, sinon elle ne tiendrait la place ouverte que sur celle qui la porterait ; repliée
  elle ne coûte rien au terrain. Une réussite n'a donc rien à annoncer — la ligne l'a montré —, et
  seul un échec mérite encore une phrase. La composition rafraîchie n'est reprise qu'une fois la
  ligne refermée : redessiner l'écran tient l'image le temps d'une fermeture, qui y passerait
  entière.
- Synchronisation manuelle avec un serveur SQLite, authentifié par un jeton personnel : elle part
  avec ce même geste, en silence et sans rien réclamer. Sans jeton elle n'a rien à faire et se
  tait : reprocher un jeton absent à qui demandait une actualisation, c'est répondre à côté.
- Export JSON des observations via le partage Android, proposé au pied de la carte « Mes
  observations » et seulement lorsqu'il y a quelque chose à exporter.

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

L’accueil et le calendrier des vrais matchs n’ont rien à configurer : le flux ESPN est public et
ne demande ni clé ni compte. Il faut seulement que le serveur ait accès à Internet.

Le serveur charge automatiquement le fichier `.env` à la racine du projet, quel que soit le
dossier de lancement — il n’y cherche plus que `FONOTE_TOKEN`. Les variables déjà exportées ont
priorité. Le fichier `.env` est ignoré par Git ; son contenu n’est jamais exécuté comme du code.

Le serveur sert de proxy limité aux compétitions, équipes et matchs : l’application ne parle
jamais au fournisseur directement, et ce qui sort du serveur est le contrat Fonote, pas la
réponse brute d’ESPN.

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
FONOTE_BACKEND_IMAGE=registry.mmyumu.fr/fonote-backend:1.1.0
```

Sans `FONOTE_TOKEN`, `docker compose` refuse de démarrer plutôt que de lancer un serveur qui
répondrait 401 à tout. Sans accès à Internet, le serveur démarre et l'application reste en
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

Parcours Android à vérifier sur appareil : composer une note à plusieurs joueurs sans quitter le terrain, une note libre par `+` qui nomme un joueur, puis la passer en note de match en retirant sa minute, une note tactique (remplir le terrain, déplacer un joueur, tracer une passe puis une course, encadrer une ligne entière et la déplacer d'un bloc, donner une action à un joueur du tableau, vérifier qu'elle apparaît au bilan, revenir par « Mes observations » et rouvrir le schéma d'un toucher), noter hors connexion, fermer et rouvrir en cours de composition, ajouter un commentaire, vérifier le bilan, synchroniser deux fois, annuler puis resynchroniser, importer sur un second appareil. Les notes ne doivent pas être dupliquées ou réapparaître après suppression.

## Contrat du serveur et persistance

Toutes les routes exigent `Authorization: Bearer <jeton>` :

| Route | Réponse / effet |
|---|---|
| `GET /v1/matches` | Le match embarqué, ses 22 joueurs et sa provenance |
| `GET /v1/football/competitions` | Les treize compétitions que le serveur sait servir |
| `GET /v1/football/matches?lineups=1` | Le calendrier, en disant quelles compositions sont déjà publiées |
| `GET /v1/football/competitions/{code}/teams` | Équipes d’une compétition, pour choisir ses suivis |
| `GET /v1/football/matches?dateFrom=…&dateTo=…` | Matchs d’une période pour l’accueil et le calendrier |
| `GET /v1/football/matches/{id}` | Détail, composition publiée, remplaçants, déroulé du match et heures réelles |
| `POST /v1/operations` | Enregistre une opération ; renvoie `{seq, operation}` |
| `GET /v1/operations` | Journal complet ordonné, utilisable par Android ou Quest |

Une opération possède `id` (UUID), `note_id` (UUID) et `kind`. Étendre une note en réémet une sous le même `note_id` avec un `id` neuf : le journal garde les deux, le lecteur retient la dernière et conserve la place chronologique de la première.

- `note` : `match_id`, `minute` (entier, ou `null` pour une note sur le match entier) et `entries`, la liste des `{player_id, action}` du moment — un joueur au plus une fois, liste vide pour une note écrite. Une note qui ne crédite personne, libre ou de match, peut **nommer** : `team` (`home`, `away` ou `null`) ou `players`, une liste d'identifiants, l'un ou l'autre et jamais les deux ; une note d'actions refuse les deux. Une note sans minute ne porte aucune action. Les notes écrites avant cette forme portaient `player_id` et `action` à la racine ; le journal étant immuable, le serveur et le client acceptent toujours les deux.
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

## Séquences tactiques et keyframes

Le temps du tableau décrit l’action (0 à 120 secondes, par dixièmes), indépendamment de la
minute du match. Touchez le temps pour saisir un instant précis, ou déplacez le curseur.

### Placer et animer

- Sans outil, glisser un joueur modifie sa position **à cet instant**. Entre deux keyframes,
  le geste en ajoute une ; les positions précédentes et suivantes restent fixes.
- Le placement initial est conservé à 0 s lors du premier déplacement ultérieur. Exemple :
  placer en A, aller à 1,2 s, déplacer en B, lire avec **▶**. À la dernière clé,
  la lecture repart du début ; elle s’arrête à la dernière clé ou au dernier repère.
- La lecture suit l’écran image par image : les joueurs et le ballon glissent entre deux
  dixièmes au lieu de sauter, et le curseur de la barre avance avec eux. Le ballon quitte les
  pieds du passeur et rejoint ceux du receveur progressivement. Une pause revient au dixième
  affiché, sur lequel l’édition reprend.
- **‹ ◇ ›** concerne le joueur sélectionné, la sélection de plusieurs joueurs ou le ballon.
  Les flèches rejoignent la keyframe précédente/suivante. Le losange neutre ajoute une clé
  sans changer l’animation ; le losange orange plein supprime la clé courante. Un losange
  partiel signifie que certains joueurs du groupe ont une clé ici : toucher complète le groupe.
- **Éditer… → Keyframes** permet aussi de changer l’instant d’une clé.
- **Éditer… → Maintenir ici jusqu’à…** fait attendre les joueurs sélectionnés avant leur appel.
- **Éditer… → Déplacer toute la trajectoire** arme un déplacement global pour le prochain geste.
- **Course** trace un déplacement depuis le joueur. **↝** propose une durée automatique,
  des durées prédéfinies et une saisie précise. Après un tracé, le curseur passe à l’arrivée
  pour enchaîner la suite de l’action. Pour un appel simultané, revenir au départ avec **‹**
  ou **Trajet… → Aller au départ**.
- **Tacle** se trace du joueur qui tacle jusqu’au joueur visé. Le tacleur court à l’allure
  d’une course et s’arrête au contact, là où le visé sera à cet instant, sans le recouvrir. Si
  le visé a le ballon, le tacleur le récupère au contact. Le trait est pointillé et finit sur
  une croix au lieu d’une flèche.

### Corriger et synchroniser

Touchez une trajectoire puis **Trajet…**, ou ouvrez **Mouvements**, pour choisir son départ,
sa durée, corriger ses extrémités avec les cercles orange ou redessiner son parcours.
Le remplacement d’une portion déjà animée est annoncé avant validation.

**▾** ouvre la chronologie collective. Glisser un bloc change son début ; glisser son bord
droit change sa durée. Les réglages restent accessibles dans le menu Mouvements.
**Lier le départ…** attache un mouvement au départ ou à l’arrivée d’un autre. Modifier ensuite
son début règle le décalage par rapport à cette référence. **Délier** conserve son temps actuel.
Seuls les mouvements liés suivent une modification ; les cycles, croisements de positions et
sorties des 120 secondes sont refusés. Supprimer une référence détache ses dépendantes en
conservant leurs temps.

Le ballon suit son porteur. Une passe vers un joueur suit sa position de réception ; un tir
termine sur un point libre. Dans le menu d’une passe, **Receveur dans cet espace…** crée une
course vers l’arrivée du ballon, synchronisée sur la passe ; remplacer une course existante
nécessite une validation. Les incohérences de possession sont signalées dans la chronologie.

**Éditer… → Repères** ajoute des signets nommés sur le temps, avec miniature, par exemple
« Réception » à 2,4 s. Ils sont dessinés en fanions au-dessus de la barre du temps ; toucher un
fanion y amène. Ils servent uniquement à naviguer : ils ne créent ni ne modifient aucune
keyframe, qui restent les rectangles dans la barre.

**↶ / ↷** annulent et rétablissent un geste complet, y compris ses conséquences temporelles et
les annotations des joueurs retirés. L’historique conserve 40 gestes pendant l’édition ouverte.
Chaque geste terminé est sauvegardé hors connexion dans une transaction SQLite ; une erreur
restaure l’état précédent. Une sélection ou une lecture ne crée pas d’opération.

### Format et vérifications

Le diagramme `version: 3` conserve les pistes des joueurs et du ballon. Chaque clé a un `id`
stable ; `after` et `offset` expriment une relation temporelle vers une autre clé. Les mouvements
sont les portions entre positions successives, identifiées par leur arrivée : une position
commune n’est jamais dupliquée. `baked` indique un parcours déjà lissé, découpé sans changer
l’animation. `steps` contient les repères `{id, name, t}`. Seul le tacle donne un `kind` à la clé d’un
joueur (`tackle`) ; les autres traits d’un joueur se lisent sur le ballon. Un tracé peut être aussi long
que le geste : il est gardé en entier pendant qu’il se dessine, puis enregistré sur 32 points
répartis à intervalles égaux le long du trait, ses deux bouts inchangés. Le serveur valide les références,
les temps résolus et l’absence de cycles. Limites : 30 pions, 120 clés par piste, 120 étapes.

Déployer le serveur compatible avant de synchroniser les nouveaux diagrammes. Les anciens
formats restent lisibles avec le lecteur existant ; aucune migration des données de test n’est
nécessaire. Ne pas rééditer une séquence v3 avec une ancienne application. Une éventuelle remise
à zéro des tests doit concerner les journaux des deux appareils et du serveur ensemble.

```bash
source scripts/android-env.sh
FONOTE_JSON_JAR="$FONOTE_ROOT/.tooling/android-studio/plugins/grazie/lib/org.json-json.jar"
javac -cp "$FONOTE_JSON_JAR" -d /tmp/fonote-checks android/app/src/main/java/fr/fonote/{Track,Diagram,Sequence,TacticalHistory}.java android/checks/fr/fonote/{Track,Diagram,Sequence}Check.java
java -ea -cp "/tmp/fonote-checks:$FONOTE_JSON_JAR" fr.fonote.TrackCheck
java -ea -cp "/tmp/fonote-checks:$FONOTE_JSON_JAR" fr.fonote.DiagramCheck json
java -ea -cp "/tmp/fonote-checks:$FONOTE_JSON_JAR" fr.fonote.SequenceCheck
python3 -m unittest discover -s backend -q
bash scripts/check-offline-android.sh
```

L’instrumentation vérifie les gestes réels, les états du losange, la navigation, l’annulation,
les annotations et l’atomicité SQLite dans un journal de test séparé.
