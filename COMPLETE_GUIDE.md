# 📘 Guide complet — DMARC Web Dashboard

Ce document explique le projet entier : à quoi il sert, comment il est construit,
pourquoi chaque décision a été prise, comment le faire tourner et comment le mettre
en ligne. Il est écrit pour que quelqu'un qui découvre le code puisse le reprendre
seul.

---

## Sommaire

1. [Le problème, et ce que le produit en fait](#1-le-problème-et-ce-que-le-produit-en-fait)
2. [Architecture](#2-architecture)
3. [Démarrer en local — Docker ou installation manuelle](#3-démarrer-en-local)
4. [Le modèle de données](#4-le-modèle-de-données)
5. [L'isolation entre organisations](#5-lisolation-entre-organisations)
6. [Comptes, connexion et rôles](#6-comptes-connexion-et-rôles)
7. [Rejoindre une organisation](#7-rejoindre-une-organisation)
8. [L'analyse de domaine et son barème](#8-lanalyse-de-domaine-et-son-barème)
9. [Les rapports DMARC : d'où ils viennent](#9-les-rapports-dmarc--doù-ils-viennent)
10. [Les écrans, un par un](#10-les-écrans-un-par-un)
11. [La console d'exploitation](#11-la-console-dexploitation)
12. [Sécurité](#12-sécurité)
13. [Configuration complète](#13-configuration-complète)
14. [Mise en ligne](#14-mise-en-ligne)
15. [Les tests](#15-les-tests)
16. [Défauts trouvés et corrigés](#16-défauts-trouvés-et-corrigés)
17. [Ce qui reste à faire](#17-ce-qui-reste-à-faire)

---

## 1. Le problème, et ce que le produit en fait

### Pourquoi DMARC existe

Le protocole d'origine du courrier électronique ne vérifie pas qui envoie. La ligne
« De : » d'un message est du texte libre : n'importe qui peut y écrire
`facturation@votre-entreprise.com`, et le destinataire verra un message qui semble
venir de vous.

Trois mécanismes se sont ajoutés par-dessus :

- **SPF** — un enregistrement DNS listant les serveurs autorisés à envoyer pour un
  domaine. Il casse au transfert : si un message est réexpédié, le serveur qui le
  relaie ne figure pas dans la liste.
- **DKIM** — une signature cryptographique apposée au message avec une clé privée,
  vérifiable grâce à une clé publique publiée en DNS. Elle survit au transfert.
- **DMARC** — relie ces deux mécanismes à l'adresse « De : » visible, dit aux
  destinataires quoi faire quand la vérification échoue (`p=none`, `p=quarantine`,
  `p=reject`), et **demande des rapports** sur ce qui a été observé.

C'est ce dernier point qui justifie ce produit : configurer DMARC déclenche un flux
quotidien de rapports XML envoyés par Google, Microsoft, Yahoo et les autres. Bruts,
ils sont illisibles.

### Les deux moitiés du produit

Le tableau de bord distingue **délibérément** deux questions différentes :

| | Question | Source |
|---|---|---|
| **Analyse** | Ce domaine *peut-il* être usurpé ? | Interrogation DNS en direct |
| **Tableau de bord** | Qu'a-t-on *réellement* envoyé en son nom ? | Rapports agrégés reçus |

Un domaine peut obtenir 100/100 à l'analyse et montrer des échecs au tableau de bord.
Ce n'est pas une contradiction : la configuration est correcte, mais un expéditeur
légitime n'y figure pas — un outil d'emailing, un logiciel de facturation. La phrase
est écrite sur les deux pages, parce que sans elle l'écart ressemble à un bug.

---

## 2. Architecture

```
┌────────────────────┐        ┌──────────────────────┐        ┌──────────────┐
│  React 19 + Vite   │  /api  │  Spring Boot 3.3     │  JDBC  │  MariaDB     │
│  (interface)       │───────▶│  Java 17             │───────▶│  (données)   │
└────────────────────┘        └──────────────────────┘        └──────────────┘
                                    │        │
                        DNS 8.8.8.8 │        │ IMAP / SMTP
                                    ▼        ▼
                         ┌───────────────┐  ┌────────────────────┐
                         │ Analyse d'un  │  │ Boîte de rapports  │
                         │ domaine       │  │ + envoi des emails │
                         └───────────────┘  └────────────────────┘
```

**En développement**, l'interface tourne sur son propre port (5173) et relaie `/api`
vers le backend (8000). **En production**, l'interface est construite *dans* le
backend : un seul processus, un seul port, une seule origine — et `/api` cesse d'être
un appel cross-origin.

### Organisation du code

```
backend/src/main/java/com/teknologiia/dmarc/
├── config/          SecurityConfig, CorsConfig, DataInitializer, SinglePageAppConfig
├── controller/      Les points d'entrée HTTP, un par domaine fonctionnel
├── dto/             Les formes échangées — des records Java, immuables
├── model/           Les entités JPA
├── repository/      L'accès aux données, chaque méthode portée par organisation
├── security/        JWT, TOTP, politique de mot de passe, chiffrement, accès plateforme
├── service/         La logique métier
└── web/             Limitation de débit, validation de domaine, gestion des erreurs

frontend/src/
├── components/      Découpés par écran : admin/, analysis/, dashboard/, platform/, ui/
├── context/         Auth, thème, notifications, domaine sélectionné
├── pages/           Un composant par écran
├── services/api.js  Tous les appels HTTP, en un seul endroit
└── utils/roles.js   Les rôles, normalisés
```

### Pourquoi ces choix

**Spring Boot** — l'analyse DMARC demande des requêtes DNS brutes (`dnsjava`), du
parsing XML durci, de la lecture IMAP et de la génération de PDF. Ces bibliothèques
sont matures sur la JVM.

**MariaDB** plutôt que PostgreSQL — XAMPP l'installe par défaut, ce qui supprime une
étape d'installation. Le pilote parle aussi à MySQL si le serveur change.

**React sans framework** — l'application est une interface d'administration
authentifiée : il n'y a pas de rendu serveur à gagner ni de référencement à optimiser
sur des pages protégées.

---

## 3. Démarrer en local

Deux chemins. Docker n'installe rien d'autre que Docker ; l'installation manuelle
donne le rechargement à chaud pendant le développement.

### A. Avec Docker

**Prérequis :** Docker Desktop uniquement. Ni Java, ni Node, ni base de données.

```bash
cp .env.example .env        # puis le lire : chaque valeur a un défaut qui marche
docker compose up --build
```

Le tableau de bord est sur **http://localhost:8000** — interface et API sur le même
port. Le mot de passe du premier administrateur est dans le log :

```bash
docker compose logs app | grep -A3 "Generated password"
```

| Commande | Effet |
|---|---|
| `docker compose up -d` | Démarre en arrière-plan |
| `docker compose logs -f app` | Suit le log de l'application |
| `docker compose down` | Arrête, **garde** les données |
| `docker compose down -v` | Arrête et **détruit la base** |

#### Ce que fait l'image

Le [`Dockerfile`](Dockerfile) a trois étages, et chacun existe pour une raison
précise :

| Étage | Base | Ce qu'il produit |
|---|---|---|
| `frontend` | `node:20-alpine` | L'interface compilée, écrite dans les ressources du backend |
| `backend` | `maven:3.9-eclipse-temurin-17` | Le jar exécutable, interface incluse |
| *(final)* | `eclipse-temurin:17-jre-alpine` | Le jar et un JRE. Rien d'autre |

Trois décisions valent d'être expliquées :

**Un seul port.** Vite écrit sa sortie dans
`backend/src/main/resources/static`, donc le jar sert l'interface *et* l'API. Un
seul port à publier, une seule origine, et `/api` cesse d'être un appel
cross-origin — ce qui supprime toute la question CORS en production.

**Un JRE, pas un JDK.** Le compilateur, le débogueur et le reste de la chaîne
d'outils n'ont rien à faire dans un conteneur qui tourne, et chacun d'eux est à
portée de main de ce qui parviendrait à entrer.

**Un utilisateur non privilégié.** Le processus tourne en tant que `dmarc` et
n'écrit nulle part en dehors de `/tmp`. Un processus qui ne peut pas écrire dans sa
propre installation ne peut pas être amené à l'écraser.

#### Ce que le contexte de build ne voit jamais

Le [`.dockerignore`](.dockerignore) commence par `backend/config/`, et c'est
l'entrée qui compte. Ce répertoire contient le mot de passe d'application de la
boîte mail et la clé qui déchiffre tous les mots de passe de boîtes stockés. Sans
cette ligne, ils seraient copiés dans une couche de l'image — où ils restent
lisibles pour toujours, **même si une couche ultérieure les supprime**, et où ils
voyagent avec l'image partout où elle est poussée.

#### Les deux clés

Elles ont chacune un défaut vide pour qu'un premier lancement fonctionne sans
cérémonie, et la page Platform les signale tant qu'elles ne sont pas posées.

```bash
openssl rand -base64 48     # JWT_SECRET
openssl rand -base64 32     # SECRETS_KEY
```

- **`JWT_SECRET` absent** : une clé jetable est générée à chaque démarrage, donc
  tout le monde est déconnecté à chaque redémarrage du conteneur — et elle est
  écrite dans le log, où quiconque la lit peut forger un jeton pour n'importe quel
  compte.
- **`SECRETS_KEY` absent** : l'application **refuse** de stocker un mot de passe de
  boîte mail plutôt que de le garder en clair. Aucune organisation ne peut donc
  faire collecter ses rapports automatiquement.

#### La base, et la seule copie qui existe

Le volume `db-data` est nommé, donc `docker compose down` le conserve et seul
`down -v` le détruit. **Rien ne le sauvegarde.** Traiter ce volume comme la seule
copie des données, parce que c'en est une :

```bash
docker compose exec db mariadb-dump -u root -p"$DB_ROOT_PASSWORD" dmarc_dashboard > backup.sql
```

Le port 3306 n'est délibérément **pas publié**. L'application joint la base par son
nom sur le réseau Compose ; le publier l'exposerait à toute la machine et, sur un
poste de développement, entrerait en collision avec le MySQL que XAMPP y fait déjà
tourner.

`DB_DDL_AUTO` vaut `update` par défaut : c'est ce qui permet à Hibernate de créer
le schéma sur un volume vide, donc ce qui fait marcher le premier lancement. Une
fois le schéma en place et les données réelles, passer à `validate` dans `.env` —
l'application refuse alors de démarrer sur un désaccord au lieu de modifier des
tables vivantes pour coller à une entité mal tapée.

### B. Sans Docker

**Prérequis :** Java 17 ou plus, Node 20 ou plus, MariaDB ou MySQL.

#### Trois commandes

```bash
# 1. La base
mysql -u root -e "CREATE DATABASE dmarc_dashboard CHARACTER SET utf8mb4"

# 2. Le backend — http://localhost:8000
cd backend
./mvnw spring-boot:run

# 3. L'interface — http://localhost:5173
cd frontend
npm install
npm run dev
```

Le schéma est créé par Hibernate au premier démarrage (`DB_DDL_AUTO=update`).

#### Les secrets locaux

Ils vont dans `backend/config/application.properties`, ignoré par git. Spring Boot lit
ce répertoire **avant** le fichier packagé et fusionne clé par clé : seuls les
réglages qui y figurent sont surchargés.

Partir de [`backend/config.example.properties`](backend/config.example.properties).

> ⚠️ Le backend doit être lancé **depuis le répertoire `backend`** pour que ce fichier
> soit pris en compte. En conteneur ce fichier n'existe pas : tout passe par des
> variables d'environnement, lues depuis `.env`.

### Le premier compte

Quel que soit le chemin choisi, au tout premier démarrage — et **seulement quand la
base ne contient aucun compte** — un administrateur est créé, et son mot de passe
généré est affiché **une seule fois** dans le log :

```
============================================================
 Created the administrator account 'admin'.
 Generated password: xxxxxxxxxxxx
 This is shown once and is not stored in clear anywhere.
============================================================
```

La condition « aucun compte » compte. La version précédente vérifiait l'existence du
nom `admin` : supprimer ce compte le faisait donc réapparaître au redémarrage suivant,
avec un nouveau mot de passe dans le log que personne n'attendait.

---

## 4. Le modèle de données

```
Organization ──┬── User ──┬── RecoveryCode
               │          └── EmailVerificationToken
               ├── Invitation
               ├── OrganizationDomain      (domaine réclamé + preuve DNS)
               ├── MailboxSettings         (boîte IMAP, mot de passe chiffré)
               ├── DmarcReport ── DmarcRecord
               ├── DomainAnalysis
               └── Alert
```

**`Organization`** est la racine de tout. Chaque table métier porte une clé vers elle,
et c'est ce qui rend l'isolation possible.

**`DmarcReport`** correspond à un fichier XML reçu : qui l'a envoyé, pour quel
domaine, sur quelle période, avec quelle politique publiée. **`DmarcRecord`** est une
ligne à l'intérieur : une adresse IP source, un volume, et les résultats SPF et DKIM.

**`DomainAnalysis`** conserve chaque analyse avec son score, les enregistrements DNS
lus et les recommandations, sérialisés en JSON. Une analyse dont `organization` vaut
`NULL` est un **scan public anonyme**, lancé depuis la page d'accueil.

---

## 5. L'isolation entre organisations

C'est la promesse centrale du produit, et elle est affichée sur la page d'accueil. Un
rapport DMARC agrégé nomme **chaque adresse IP** qui envoie au nom d'un domaine :
c'est une carte de l'infrastructure de messagerie de son propriétaire.

### Comment elle est tenue

Chaque méthode de dépôt prend un `organizationId`. Les méthodes héritées de
`JpaRepository` qui traversent les organisations — `findAll()`, `findById()`,
`count()` — ne sont **pas** utilisées pour les lectures utilisateur, et les interfaces
le disent en commentaire :

```java
/**
 * Les rapports sont portés par organisation.
 *
 * Chaque méthode prend un organizationId. findAll(), findById() et count()
 * hérités ne sont délibérément pas utilisés pour les lectures utilisateur —
 * ils traversent tous les locataires.
 */
public interface DmarcReportRepository extends JpaRepository<DmarcReport, Long> {
    Optional<DmarcReport> findByIdAndOrganizationId(Long id, Long organizationId);
```

`findByIdAndOrganizationId` plutôt que `findById` : un identifiant appartenant à une
autre organisation ne correspond simplement à rien, au lieu de renvoyer une donnée
qu'il faudrait ensuite penser à filtrer.

### La seule exception

`PlatformService` interroge délibérément sans portée — c'est l'objet même de la
classe, et elle est la seule autorisée à le faire. Elle ne lit que des **compteurs,
des dates et de la santé**, jamais un contenu.

---

## 6. Comptes, connexion et rôles

### Inscription et activation

1. Quelqu'un s'inscrit avec un nom d'utilisateur, une adresse et un mot de passe.
2. Le compte est créé **désactivé**. Spring Security refuse de l'authentifier.
3. Un jeton à usage unique, valable 24 h, part par email.
4. Le lien active le compte.

Sans serveur SMTP configuré, le lien est écrit dans le log — suffisant en local,
inutile pour qui que ce soit d'autre.

Trois issues distinctes, avec trois codes différents, parce que ce ne sont pas la
même chose :

| Situation | Code | Message |
|---|---|---|
| Lien valide | 200 | Compte activé |
| Lien **déjà utilisé** | **409** | Déjà activé — le compte fonctionne, connectez-vous |
| Lien expiré ou inconnu | 400 | Lien invalide |

Le 409 existe parce qu'un jeton dépensé signifie que **quelqu'un a suivi le lien**,
donc que le compte est actif. L'annoncer comme un échec alarme sur un compte qui va
parfaitement bien.

### La politique de mot de passe

Un seul endroit décide : `PasswordPolicy`. Utilisée par l'inscription, la création par
un administrateur et le changement par l'utilisateur — une seule définition, donc les
règles ne peuvent pas diverger entre les points d'entrée.

- 12 caractères minimum
- au moins trois classes parmi majuscules, minuscules, chiffres, symboles
- pas de mot courant (`password`, `azerty`, `teknologiia`…)
- ni le nom d'utilisateur ni la partie locale de l'adresse
- pas de suite de 4 caractères identiques ou consécutifs

Le générateur **retire tant que le résultat ne satisfait pas ces mêmes règles**. Sans
cela, environ un tirage sur quelques centaines produisait un mot de passe que la
validation refusait ensuite — un administrateur en générait un pour un collègue et se
le voyait rejeter.

### Les rôles

| Rôle | Peut |
|---|---|
| **Administrator** | Tout, y compris gérer les comptes, inviter, réclamer un domaine |
| **Analyst** | Lancer des analyses, importer des rapports, agir sur les alertes |
| **Viewer** | Tout lire, ne rien changer |

Appliqués dans `SecurityConfig`, pas seulement cachés dans l'interface :

```java
.requestMatchers("/api/admin/users/**").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/api/analysis/domain").hasAnyRole("ADMIN", "ANALYST")
.requestMatchers(HttpMethod.PATCH, "/api/alerts/**").hasAnyRole("ADMIN", "ANALYST")
// Tout le reste sous /api/admin reste administrateur par défaut : un nouvel
// endpoint ajouté là est restreint tant qu'on n'en a pas décidé autrement.
.requestMatchers("/api/admin/**").hasRole("ADMIN")
```

### La vérification en deux étapes

TOTP conforme à la **RFC 6238**, compatible Google Authenticator, Microsoft
Authenticator et 1Password. Implémenté sans dépendance : l'algorithme est un HMAC du
compteur de 30 secondes, tronqué à six chiffres.

**L'ordre compte.** Un secret est généré au début de l'inscription mais ne prend effet
que lorsque la personne prouve qu'elle sait en lire un code. Sinon, une inscription
abandonnée à mi-chemin enfermerait le compte hors de sa propre connexion.

À l'activation, **dix codes de secours** sont remis, affichés une seule fois car
stockés hachés comme des mots de passe. Chacun ouvre une session une fois.

**La connexion devient un parcours en deux étages :**

```
POST /api/auth/login        →  { mfa_required: true, mfa_token: "..." }   (pas de session)
POST /api/auth/login/2fa    →  { access_token: "..." }                    (session)
```

Le jeton de défi est signé avec la même clé qu'un jeton de session — `validateToken`
accepte donc les deux. Ce qui les sépare est une revendication `purpose`, et le fait
que `JwtAuthenticationFilter` **refuse** tout jeton qui la porte. Sans ce garde-fou,
connaître un mot de passe suffirait à contourner le second facteur : le jeton remis
pour *ne pas avoir terminé* une connexion ouvrirait lui-même tout.

---

## 7. Rejoindre une organisation

Le problème : un collègue s'inscrit de son côté avec son adresse professionnelle. Il
obtient sa propre organisation, portant le même nom d'entreprise, avec un tableau de
bord vide. Les deux ne se rencontrent jamais.

Deux mécanismes le résolvent.

### L'invitation

Un administrateur saisit une adresse et un rôle. Un lien à **usage unique**, valable
**7 jours**, révocable avant utilisation, part par email — et s'affiche aussi à
l'écran avec un bouton copier, ce qui permet de donner un accès immédiat même sans
serveur de messagerie.

L'invité choisit **lui-même** son nom d'utilisateur et son mot de passe. Ce dernier ne
transite jamais par un canal que l'administrateur ne contrôle pas.

C'est pour cette raison que le formulaire « créer un compte » a été supprimé : il
générait un mot de passe que l'administrateur devait transmettre par message ou par
téléphone, et jusqu'à son changement celui qui l'avait émis pouvait se connecter à la
place de l'autre.

### Le domaine vérifié

Une organisation réclame `example.com`, publie un enregistrement TXT prouvant qu'elle
le possède, et **toute inscription avec une adresse à ce domaine la rejoint
automatiquement** avec le rôle prévu.

```
_teknologiia-verify.example.com   TXT   teknologiia-verify=<jeton>
```

Tant que la preuve n'est pas publiée, la réclamation **n'accorde rien** — c'est ce qui
empêche de réclamer une entreprise qu'on ne possède pas. Et une trentaine de
fournisseurs publics (Gmail, Outlook, Yahoo…) ne peuvent pas être réclamés du tout :
celui qui y parviendrait absorberait toutes les inscriptions futures les utilisant.

---

## 8. L'analyse de domaine et son barème

### Ce qui est interrogé

Cinq enregistrements, lus en direct sur `8.8.8.8`, sans cache :

| Contrôle | Où | Ce qu'on y cherche |
|---|---|---|
| **DMARC** | `_dmarc.<domaine>` TXT | `v=DMARC1`, politique `p=`, adresse `rua=` |
| **SPF** | `<domaine>` TXT | `v=spf1`, le qualificatif final, le budget de recherches DNS |
| **DKIM** | `<sélecteur>._domainkey.<domaine>` TXT | la clé publique et sa taille |
| **MX** | `<domaine>` MX | l'existence de serveurs de réception |
| **BIMI** | `default._bimi.<domaine>` TXT | rapporté, jamais noté |

**DKIM est le cas difficile** : un sélecteur ne s'énumère pas depuis le DNS, on ne
peut que deviner des noms. Le service essaie d'abord les sélecteurs **réellement
observés dans les rapports de l'organisation** — ce que ses propres rapports agrégés
nomment — puis une quarantaine de noms courants.

`redirect=` en SPF est suivi : sans cela, un enregistrement parfaitement strict comme
celui de facebook.com se lit comme n'ayant aucun qualificatif.

### Le barème

Il vit dans **un seul endroit** : `ScoringModel`. Le moteur y lit ses valeurs, et
`/api/analysis/scoring-model` publie les mêmes constantes à l'interface.

| Contrôle | Poids | Attribution |
|---|---|---|
| **DMARC** | 40 | `p=reject` 40 · `p=quarantine` 28 · `p=none` 12 · absent 0 · **−6** sans `rua=` |
| **SPF** | 30 | `-all` 30 · `~all` 22 · aucun `all` 12 · `?all` 10 · `+all` 0 |
| **DKIM** | 20 | clé ≥ 2048 bits 20 · clé < 2048 bits 12 · absente 0 · **exclue** si aucun sélecteur ne répond |
| **MX** | 10 | présents 10 · absents 0 |
| **BIMI** | — | rapporté, jamais noté |

Dépasser les 10 recherches DNS autorisées par la RFC 7208 §4.6.4 est **signalé, pas
déduit** : les destinataires cessent d'évaluer au-delà, donc SPF commence à échouer
pour du courrier légitime — il faut le corriger même si le score ne bouge pas.

**Le score est la part des points *applicables* obtenus**, pas un total courant. Un
contrôle indéterminable — DKIM quand aucun sélecteur ne répond — quitte le numérateur
**et** le dénominateur, pour qu'une clé illisible ne se lise jamais comme une clé
absente.

| Note | Plage |
|---|---|
| A+ | 90–100 |
| A | 80–89 |
| B | 70–79 |
| C | 60–69 |
| D | 50–59 |
| F | 0–49 |

> **Pourquoi une source unique.** Auparavant les nombres étaient écrits en dur dans le
> moteur *et* décrits en prose dans un composant React. Les deux avaient divergé sur
> **huit valeurs** : BIMI était crédité de 5 points jamais attribués, `p=quarantine`
> annoncé à 30 au lieu de 28, et les bornes de notes décalées d'une bande entière.
> `ScoringModelTest` analyse maintenant des enregistrements fabriqués et échoue si le
> modèle publié et le moteur cessent de s'accorder.

---

## 9. Les rapports DMARC : d'où ils viennent

### Le principe

Un enregistrement DMARC contenant `rua=mailto:rapports@example.com` demande aux
destinataires d'envoyer un rapport quotidien à cette adresse. C'est la seule source de
données réelles du tableau de bord.

### Deux chemins d'entrée

**L'import manuel** — glisser des fichiers `.xml`, `.xml.gz` ou `.zip` dans l'écran
Admin. Les archives sont décompressées côté serveur, les rapports déjà connus sont
ignorés.

**La collecte automatique** — chaque organisation configure **sa propre** boîte IMAP
depuis l'écran Admin : serveur, adresse, mot de passe d'application. Un collecteur
planifié la visite toutes les 15 minutes.

> **Pourquoi par organisation.** La boîte était configurée une seule fois pour tout le
> serveur. Deux organisations qui cliquaient sur « collecter » lisaient donc **la
> même** boîte, et celle qui cliquait s'appropriait ce qu'elle y trouvait.

### Comment la collecte se comporte

**La boîte est ouverte en lecture seule.** Aucun message n'est jamais modifié.

Une version antérieure sélectionnait les messages **non lus** et marquait chaque
message traité comme lu. Deux conséquences que personne n'avait demandées : pointée
sur une vraie boîte, elle a marqué comme lus des centaines de messages personnels sans
rapport ; et comme IMAP renvoie les résultats du plus ancien au plus récent, un
rapport arrivant derrière un arriéré de courrier non lu n'était **jamais atteint**.

La sélection se fait maintenant **par date, du plus récent au plus ancien** : depuis la
veille du dernier passage réussi, avec un chevauchement volontaire d'un jour pour
qu'un rapport arrivé pendant un passage ne tombe pas entre deux fenêtres. Savoir ce
qui a déjà été pris se décide par l'identifiant de rapport enregistré en base, pas par
un drapeau posé dans la boîte de quelqu'un.

Le résultat de chaque passage est enregistré et affiché : une boîte qui échoue ne
reste pas silencieuse.

### Le mot de passe de la boîte

Un mot de passe de compte est **haché** : personne n'a besoin de l'original. Un mot de
passe de boîte doit être présenté à IMAP à chaque passage : il est donc **chiffré** en
AES-GCM, avec un nonce distinct par valeur — deux mots de passe identiques ne
produisent pas le même chiffré.

La clé vient de la configuration et n'est jamais générée à la volée : une clé
regénérée à chaque démarrage rendrait illisibles tous les mots de passe déjà stockés.
**Sans clé, l'application refuse d'en stocker un** plutôt que de le garder en clair.
L'API ne le renvoie jamais — le DTO de réponse n'a pas de champ mot de passe.

### Le durcissement du parsing

Un rapport DMARC est un fichier XML venant de l'extérieur.

- **XXE désactivé** — `disallow-doctype-decl` à `true`, et le résolveur d'entités lève
  une exception. Sans cela, un rapport fabriqué peut faire lire des fichiers du
  serveur.
- **Décompression bornée** — 64 Mo maximum, 500 entrées par archive. Une archive de
  quelques kilo-octets peut sinon se décompresser en gigaoctets.
- **Navigation par enfant direct** plutôt que `getElementsByTagName` : `<dkim>` et
  `<spf>` apparaissent sous `<policy_evaluated>` *et* sous `<auth_results>`, et une
  recherche globale mélangeait les deux.

---

## 10. Les écrans, un par un

### Landing (public)

Explique DMARC à quelqu'un qui n'en a jamais entendu parler, et propose une analyse
gratuite. Les seules dates qui y figurent sont réelles : exigences Google et Yahoo
depuis février 2024, Microsoft depuis mai 2025. Aucun témoignage, aucun logo client,
aucun chiffre d'usage inventé.

### Dashboard

Ce qui a été **réellement envoyé** : volume, taux de réussite SPF/DKIM/DMARC,
répartition des politiques, principaux expéditeurs, et la posture de configuration par
domaine — le plus faible en premier.

### Reports

Chaque rapport stocké, filtrable par domaine et par période, exportable en **CSV**
(avec BOM UTF-8, pour qu'Excel n'abîme pas les accents) ou en **PDF de marque**.

### Alerts

Pics de volume, taux d'échec dépassant le seuil, et constats issus des analyses.

### Analysis

Un bandeau de posture calculé sur l'historique, la recherche avec les domaines récents
en un clic, le résultat avec sa jauge et **la répartition des points**, les
recommandations groupées par urgence, le barème exact servi par le moteur, et
l'historique avec l'écart depuis le contrôle précédent de chaque domaine.

### Admin

Trois sections nommées, dans l'ordre où les questions arrivent :

- **Access** — inviter un collègue, réclamer un domaine email
- **Members** — les comptes, leur rôle, leur second facteur, leur date d'arrivée
- **Report intake** — import de fichiers, et la boîte aux lettres de l'organisation

Au-dessus, six tuiles d'exploitation : comptes par rôle, invitations réellement en
attente, domaines vérifiés, rapports et période couverte, état de la boîte, domaines
les plus faibles.

### Settings

Profil, changement de mot de passe, **vérification en deux étapes**, ce que le rôle
permet exactement, et les faits du déploiement lus dans le processus : version, base,
Java, durées de session. Un encadré « Before publishing » liste ce qui reste à
corriger et se vide de lui-même à mesure.

---

## 11. La console d'exploitation

Distincte de l'écran Admin. `/admin` sert l'**administrateur d'une organisation** ;
`/platform` sert **l'exploitant du service**.

### Qui y accède

La liste vient de la configuration du déploiement, **jamais de la base** :

```properties
app.platform.operators=Sultan
```

Un administrateur d'organisation peut créer des comptes et changer des rôles ; rien de
cela ne doit pouvoir devenir un accès à l'ensemble du service. Pour obtenir ce statut,
il faut pouvoir écrire dans la configuration du serveur — c'est-à-dire déjà le faire
tourner.

Un compte non habilité reçoit **404** et non 403 : il n'a pas à apprendre que cette
console existe. L'exploitant ne voit plus, dans son menu, que **Platform** et
**Settings** — son organisation ne contient aucun rapport, les autres écrans ne lui
montreraient qu'une version vide du travail d'autrui.

### Ce qu'elle montre

Ce qui demande une action d'abord : boîtes en échec, clés manquantes, schéma
auto-modifiable, adresse publique restée sur localhost. Puis les compteurs, la courbe
des inscriptions sur 14 jours, l'état du processus (uptime, mémoire, taille du
schéma), et **chaque organisation avec tous ses comptes** — rôle, second facteur,
statut, date, et un marqueur sur ceux qui utilisent encore un mot de passe qu'ils
n'ont pas choisi.

Deux actions : désactiver ou réactiver n'importe quel compte — sauf le sien, parce que
se verrouiller hors de la seule console qui pourrait le déverrouiller n'est pas un
état qu'il faut pouvoir atteindre — et supprimer une organisation **vide**, refusé dès
qu'elle contient un compte, un rapport ou une analyse.

### La console base de données

Les tables du schéma, leurs lignes, la recherche, la pagination, la suppression de
lignes et le vidage de tables. L'équivalent d'un client MySQL, en plus lisible.

Trois différences avec un client MySQL, et leur raison : **MySQL n'écoute que sur
localhost, cette page est publiée.**

1. **Le mot de passe est redemandé** avant toute suppression. Une session laissée
   ouverte devient bonne à lire, pas à effacer.
2. **`users` et `organizations` ne se vident pas en un clic** — cette action
   effacerait l'exploitant lui-même, et plus personne ne pourrait revenir en arrière.
   Les lignes restent supprimables une par une.
3. **Les colonnes de secrets sont masquées** par défaut ; un bouton les révèle, et
   cette révélation est tracée dans le log.

Les noms de table ne peuvent pas être passés en paramètre SQL. Ils ne sont donc jamais
pris tels qu'écrits dans la requête : chacun est confronté au schéma vivant, et c'est
**l'orthographe du schéma** qui atteint la requête. Un nom qui n'est pas une table
réelle est refusé avant qu'aucun SQL ne soit construit.

---

## 12. Sécurité

| Sujet | Ce qui est en place |
|---|---|
| Mots de passe | BCrypt, politique unique, générateur qui se relit |
| Sessions | JWT signé HS256, sans état, clé refusée sous 32 octets |
| Second facteur | TOTP RFC 6238, codes de secours hachés, jeton de défi inerte |
| Secrets stockés | AES-GCM, nonce par valeur, clé hors base et hors dépôt |
| Rôles | Appliqués dans la chaîne de filtres, pas seulement masqués |
| Isolation | Chaque lecture portée par organisation |
| XML | XXE désactivé, décompression bornée |
| Débit | Seau à jetons sur connexion, inscription, scan public |
| En-têtes | CSP, `X-Frame-Options: DENY`, HSTS, `Referrer-Policy` |
| Erreurs | Le texte des exceptions n'est jamais renvoyé au client |

### Deux points qui méritent une explication

**L'adresse du client derrière un proxy.** `X-Forwarded-For` est une chaîne à laquelle
chaque relais ajoute. Ce qu'un client envoie arrive **à gauche** ; ce que le proxy de
confiance a lui-même observé se trouve **à droite**. Lire l'entrée la plus à gauche —
ce que faisait le scanner public — permet de faire varier un en-tête choisi et
d'obtenir un quota neuf à chaque requête. Seule celle de droite est garantie par le
proxy.

**Une origine propre n'est jamais bloquée.** Vite génère
`<script type="module" crossorigin>`, ce qui met ces requêtes en mode CORS : le
navigateur envoie un `Origin` même pour des fichiers du même site. Déployée derrière
un tunnel, cette origine ne figurait pas dans la liste configurée, le filtre répondait
403 avec un corps vide, et le tableau de bord s'affichait **entièrement blanc**. La
configuration CORS autorise désormais toujours l'origine propre de la requête, quelle
qu'elle soit — comparée à l'en-tête `Host`, parce que derrière un proxy le serveur voit
son propre port pendant que le navigateur envoie l'adresse publique.

---

## 13. Configuration complète

Tout est surchargeable par variable d'environnement.

### Base de données

```properties
DB_URL=jdbc:mariadb://localhost:3306/dmarc_dashboard
DB_USERNAME=root
DB_PASSWORD=
DB_DDL_AUTO=update     # 'validate' en production
```

### Sécurité

```properties
JWT_SECRET=            # openssl rand -base64 48 — sinon regénérée à chaque démarrage
JWT_EXPIRATION_MS=3600000
SECRETS_KEY=           # openssl rand -base64 32 — sans elle, pas de boîte configurable
PLATFORM_OPERATORS=    # noms d'utilisateur ayant accès à la console d'exploitation
APP_TRUST_PROXY_HEADERS=false   # 'true' derrière un proxy que vous contrôlez
APP_CORS_ORIGINS=http://localhost:5173
```

### Messagerie sortante

```properties
MAIL_HOST=             # smtp.office365.com ou smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=         # mot de passe d'application, pas celui du compte
MAIL_FROM=
PUBLIC_URL=http://localhost:5173   # l'adresse que les liens envoyés visent
```

> Microsoft 365 et Gmail refusent tous deux un mot de passe de compte ordinaire en
> SMTP. Sur Microsoft 365, **SMTP AUTH doit en plus être autorisé** pour la boîte : il
> est désactivé par défaut sur les tenants créés depuis 2020.

### Collecte

```properties
IMAP_POLL_MINUTES=15
IMAP_INITIAL_DELAY_MS=60000   # délai avant le premier passage après démarrage
```

### Premier compte

```properties
ADMIN_USERNAME=admin
ADMIN_PASSWORD=        # vide ⇒ généré et affiché une fois
ADMIN_EMAIL=
ADMIN_ORGANIZATION=
```

---

## 14. Mise en ligne

### Le principe

En développement, deux ports. Un tunnel gratuit n'en expose qu'un. La solution :
construire l'interface **dans** le backend. Les deux chemins ci-dessous font
exactement cela.

### A. Par conteneur

C'est déjà le cas dans l'image : il n'y a qu'un port à exposer. Sur un serveur avec
Docker installé :

```bash
git clone https://github.com/Sultan-zd/DMARC-Project.git
cd DMARC-Project
cp .env.example .env
```

Trois valeurs à poser dans `.env` avant d'exposer quoi que ce soit :

```properties
PUBLIC_URL=https://dmarc.example.com   # l'adresse que les gens tapent réellement
APP_TRUST_PROXY_HEADERS=true           # uniquement derrière un proxy que vous contrôlez
JWT_SECRET=<openssl rand -base64 48>   # sinon tout le monde est déconnecté à chaque redémarrage
SECRETS_KEY=<openssl rand -base64 32>  # sinon aucune boîte mail ne peut être enregistrée
```

```bash
docker compose up -d --build
```

Puis un proxy inverse devant le port 8000 pour le TLS — Caddy, nginx, Traefik — ou
un tunnel :

```bash
ngrok http 8000 --url votre-nom.ngrok-free.dev
```

> ⚠️ `APP_TRUST_PROXY_HEADERS=true` **uniquement** derrière un proxy qui écrit
> lui-même `X-Forwarded-For`. Joignable directement, cet en-tête est ce que
> l'appelant veut bien y mettre, et n'importe qui peut le faire varier pour obtenir
> un compteur de limitation neuf à chaque tentative.

### B. Sans conteneur

```powershell
.\go-online.ps1 -PublicUrl https://votre-adresse.example.com
```

Le script construit l'interface vers `backend/src/main/resources/static/`, génère une
clé de signature persistante si elle n'existe pas, règle `PUBLIC_URL` pour que les
liens des emails visent la bonne adresse, active la lecture de `X-Forwarded-For`, puis
démarre le backend.

Ensuite, dans un second terminal :

```
ngrok http 8000 --url votre-nom.ngrok-free.dev
```

### Le repli SPA

Le tableau de bord route côté navigateur. Un visiteur qui **recharge** sur `/analysis`
demande au serveur un fichier qui n'a jamais été construit. `SinglePageAppConfig`
répond alors avec la coquille HTML — sauf sous `/api`, où une adresse inexistante doit
répondre 404 et non rendre du HTML.

### Ce qu'il faut savoir sur ngrok gratuit

Les visiteurs voient d'abord une **page d'avertissement ngrok** avec un bouton « Visit
Site ». C'est ngrok, pas l'application. Et les **domaines personnalisés sont
payants** : un CNAME vers un domaine `ngrok-free.dev` résout bien, mais ngrok ne
présente aucun certificat pour ce nom et la connexion échoue au TLS.

### Avant d'ouvrir au public

- [ ] `JWT_SECRET` défini
- [ ] `SECRETS_KEY` défini
- [ ] `DB_DDL_AUTO=validate`
- [ ] `PUBLIC_URL` sur l'adresse réelle
- [ ] `APP_CORS_ORIGINS` sans localhost
- [ ] Second facteur activé sur les comptes administrateurs
- [ ] HTTPS (le tunnel le fournit ; un déploiement durable demande un vrai certificat)

L'écran Settings affiche ces points tant qu'ils ne sont pas faits.

---

## 15. Les tests

```bash
cd backend && ./mvnw test
```

**211 tests.** Ils tournent contre une base en mémoire, **imposée par le build à
chacun d'eux** via `spring.profiles.active=test` dans la configuration Surefire — une
suite capable d'atteindre une vraie base est une suite capable de la détruire.

### Ce qu'ils verrouillent

| Classe | Ce qu'elle verrouille |
|---|---|
| `ScoringModelTest` | Le barème publié correspond à ce que le moteur attribue |
| `SpfScoringTest` | Le budget de recherches DNS, le suivi de `redirect=` |
| `TotpServiceTest` | Les vecteurs de test officiels de la RFC 6238 |
| `ChallengeTokenTest` | Un jeton de défi n'ouvre pas de session |
| `MailboxIsolationTest` | Une organisation ne voit pas la boîte d'une autre |
| `AdminOverviewServiceTest` | Les compteurs restent portés par organisation |
| `AnalysisIsolationTest` | L'historique d'analyse ne traverse pas les organisations |
| `ReportServiceFilterTest` | Les filtres et la pagination des rapports |
| `DatabaseConsoleServiceTest` | Injection par nom de table, tables protégées |
| `SchemaDictionaryTest` | Chaque colonne décrite existe encore, et dans le bon ordre |
| `SpaRouteTest` | Chaque page survit à un rechargement du navigateur |
| `VerificationTokenTest` | Les trois issues d'un lien d'activation |
| `PasswordPolicyTest` | Chaque mot de passe généré satisfait la politique |
| `PlatformAccessTest` | Un nom ressemblant n'approche pas le statut d'exploitant |
| `DmarcParserServiceTest` | Le parsing d'un rapport réel, XXE compris |
| `CorsConfigTest` | Le site n'est jamais bloqué en s'adressant à lui-même |
| `DomainNameValidatorTest` | Les noms refusés avant toute requête DNS |
| `RateLimiterTest` | Le seau à jetons et son réapprovisionnement |

---

## 16. Défauts trouvés et corrigés

Ce chapitre existe parce que ces erreurs sont instructives — et parce que plusieurs
étaient invisibles jusqu'à ce qu'on regarde vraiment.

### Des données inventées présentées comme réelles

Six zones renvoyaient des données fabriquées derrière des endpoints qui semblaient
fonctionner : `ExportService` produisait des PDF vides, `UserService.getAllUsers`
répondait une liste vide, `createUser` hachait le mot de passe puis le jetait,
`StatsService` renvoyait une courbe plate codée en dur. Un générateur créait 120
rapports pour `example.com` et `test.org`, indiscernables de vrais une fois en base.
Tout a été rendu réel ou supprimé.

### Le barème affiché ne correspondait pas au moteur

Huit valeurs divergentes, dont 5 points crédités à BIMI qui n'ont jamais existé.
Corrigé à la racine par une source unique.

### Trois fuites entre organisations

`StatsService` utilisait `findAll()`, `markAllAsRead()` ne portait pas la clé, et
l'historique d'analyse renvoyait les lignes de tous les locataires — y compris le nom
de qui les avait lancées.

### La collecte abîmait la boîte de son propriétaire

Voir [§9](#9-les-rapports-dmarc--doù-ils-viennent). Environ 170 messages personnels
marqués comme lus, et le rapport recherché jamais atteint parce qu'il se trouvait
derrière deux mille messages non lus.

### La CSP rendait la page blanche en production

`default-src 'none'` convenait à une API seule. Dès que le même processus a servi
l'interface, plus aucun script ni style ne se chargeait.

### StrictMode faisait échouer chaque activation

React monte, démonte puis remonte chaque composant en développement. L'effet de la
page de vérification partait deux fois : le premier appel consommait le jeton et
activait le compte, le second recevait « déjà utilisé » — et c'était ce résultat-là
qui s'affichait. **Tout le monde** voyait « Verification failed » sur un compte qui
venait d'être activé correctement.

### Une suite de tests capable de détruire la base

Un `@SpringBootTest` sans `@ActiveProfiles("test")` s'est connecté à la vraie base
MariaDB, et le `deleteAll()` de sa préparation a vidé les rapports et les analyses. Le
profil de test est désormais imposé par le build, hors de portée d'un oubli
d'annotation.

### Un `getColumns` non limité au schéma

La console base de données remontait les colonnes de la table système
`INFORMATION_SCHEMA.USERS` mélangées à celles de la table `users` de l'application. En
MariaDB, une autre base contenant une table homonyme aurait produit la même confusion
— potentiellement une lecture ou une suppression dans la mauvaise table.

### Divers

Un `COUNT` gonflé par une jointure, un `.search-btn` fuyant d'une feuille de style à
l'autre, une casse de rôle (`ADMIN` contre `admin`) qui interdisait la page Admin à
tous les administrateurs, `.sr-only` utilisé sans jamais avoir été défini, un
`display: flex` sur un `<td>` qui sortait la cellule du modèle de tableau et coupait
les séparateurs de lignes, `CAST(x AS CHAR)` qui vaut `CHAR(1)` en H2 et tronquait la
recherche à un caractère, et une clé React basée sur le nom d'organisation alors que
deux portaient le même.

---

## 17. Ce qui reste à faire

- **HTTPS propre** — le tunnel le fournit ; un déploiement durable demande un vrai
  certificat et un reverse proxy.
- **Limitation de débit partagée** — le seau à jetons est en mémoire ; plusieurs
  instances demanderaient Redis.
- **Sauvegardes** — le volume `db-data` est la seule copie des données et rien ne le
  sauvegarde. C'est le manque le plus coûteux de cette liste : une base perdue ne se
  reconstitue pas.
- **Migrations de schéma** — Hibernate crée et modifie les tables lui-même. Flyway ou
  Liquibase rendrait chaque changement versionné et relu.
- **Découpage du bundle** — près de 800 ko en un seul fichier JavaScript.
- **Journal d'audit** — les actions d'exploitation sont tracées dans le log, pas dans
  une table consultable.
- **Rétention** — rien ne purge les vieux rapports aujourd'hui.

---

## Licence

[MIT](LICENSE) — Teknologiia
