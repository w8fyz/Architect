# Documentation complète d'Anchor

## Table des matières
1. [Introduction](#introduction)
2. [Installation et configuration](#installation-et-configuration)
3. [Concepts fondamentaux](#concepts-fondamentaux)
4. [Syntaxe et fonctions](#syntaxe-et-fonctions)
   - [La fonction fetch](#la-fonction-fetch)
   - [La fonction map](#la-fonction-map)
   - [Opérations conditionnelles avec if](#opérations-conditionnelles-avec-if)
   - [Concatenation avec concat](#concatenation-avec-concat)
   - [Manipulation des propriétés](#manipulation-des-propriétés)
5. [Exemples pratiques](#exemples-pratiques)
   - [Système de classement](#système-de-classement)
   - [Profil utilisateur](#profil-utilisateur)
   - [Analyse de données](#analyse-de-données)
6. [Optimisation des performances](#optimisation-des-performances)
7. [Dépannage](#dépannage)
8. [Référence technique](#référence-technique)

## Introduction

Anchor est un langage de script léger conçu pour interagir avec vos entités Java de manière simple et intuitive. Contrairement aux solutions complexes comme HQL ou SQL, Anchor propose une syntaxe claire et accessible même pour les utilisateurs peu familiers avec la programmation.

### Que permet Anchor?

Avec Anchor, vous pouvez:
- **Récupérer** des données depuis vos dépôts d'entités Java
- **Transformer** ces données en formats lisibles
- **Trier** et **limiter** les résultats selon vos besoins
- **Manipuler** les résultats avec des conditions et des opérations de formatage
- **Stocker** des variables intermédiaires pour un traitement étape par étape

Tout cela avec une syntaxe simple et concise qui ne nécessite pas de connaissances approfondies en programmation.

## Installation et configuration

### Prérequis
- Java 8+
- Redis (pour le stockage)
- Vos dépôts d'entités implémentant l'interface `GenericRepository`

### Initialisation d'Architect

Anchor fonctionne au sein d'Architect, qui gère les connexions Redis et l'enregistrement des dépôts:

```java
// Initialisation d'Architect avec Redis
Architect architect = new Architect()
    .setReceiver(false)
    .setRedisCredentials(new RedisCredentials(
        "localhost",  // Hôte Redis
        "password",   // Mot de passe Redis
        6379,         // Port Redis
        100,          // Taille du pool de connexions
        6             // Numéro de base de données
    ));

// Enregistrement de vos dépôts
architect.addRepositories(
    new UserRepository(),
    new RankRepository()
    // Ajoutez d'autres dépôts selon vos besoins
);

// Démarrage du service
architect.start();
```

### Création d'un script Anchor

```java
// Créer une instance de script
AnchorScript script = new AnchorScript();

// Écrire un script simple
String scriptText = """
    users = fetch("users/*");
    activeUsers = fetch("users/*/active");
    topUsers = fetch("users/*/order/rank.power:desc/limit/5");
    """;

// Exécuter le script et récupérer les résultats
AnchorScript.ScriptResult result = script.execute(scriptText).get();

// Accéder aux résultats
List<User> users = result.get("users", List.class);
List<Boolean> activeStatus = result.get("activeUsers", List.class);
List<User> top = result.get("topUsers", List.class);
```

## Concepts fondamentaux

Anchor fonctionne sur quelques concepts simples:

1. **Dépôts d'entités**: Collections d'objets que vous pouvez interroger (ex: users, ranks)
2. **Chemins d'accès**: Chaînes de caractères décrivant comment accéder aux données
3. **Variables**: Stockage temporaire des résultats pour une utilisation ultérieure
4. **Transformations**: Conversion des données brutes en formats lisibles

### Structure d'un script Anchor

Un script Anchor est composé d'une série d'instructions, chacune terminée par un point-virgule:

```
// Commentaire explicatif
variable = opération(paramètres);  // Une instruction
autreVariable = autreOperation(autreVariable);  // Une autre instruction
```

## Syntaxe et fonctions

### La fonction fetch

La fonction `fetch` est le moyen principal d'accéder aux données. Elle permet de récupérer des entités ou leurs propriétés depuis vos dépôts.

#### Syntaxe complète

```
fetch("entité/identifiant[/propriété][/order/tri][/limit/nombre]")
```

#### Paramètres détaillés

| Paramètre | Description | Obligatoire | Format |
|-----------|-------------|-------------|--------|
| entité | Le type d'entité à récupérer | Oui | Chaîne de caractères, ex: "users" |
| identifiant | L'ID spécifique ou "*" pour tout récupérer | Oui | UUID ou "*" |
| propriété | Propriété spécifique à extraire | Non | Chemin d'accès avec notation point, ex: "username" ou "rank.name" |
| order | Critère de tri | Non | Propriété + direction (":asc" ou ":desc") |
| limit | Nombre maximal de résultats | Non | Entier positif |

#### Exemples annotés

```java
// Récupère tous les utilisateurs
users = fetch("users/*");

// Récupère un utilisateur spécifique par son UUID
user = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9");

// Récupère uniquement le nom d'utilisateur
username = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9/username");

// Tri les utilisateurs par la propriété 'power' de leur rang, en ordre décroissant
powerfulUsers = fetch("users/*/order/rank.power:desc");

// Récupère uniquement les 10 premiers utilisateurs après tri
topUsers = fetch("users/*/order/rank.power:desc/limit/10");

// Récupère une propriété imbriquée pour tous les utilisateurs
rankNames = fetch("users/*/rank.name");
```

#### Accès aux propriétés imbriquées

Pour accéder à des propriétés imbriquées, utilisez la notation par point:

```
// Accède à la propriété 'name' de l'objet 'rank' de l'utilisateur
rankName = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9/rank.name");

// Accède à la taille de la collection 'friends'
friendCount = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9/friends.size");
```

#### Tri avec 'order'

Le paramètre `order` permet de trier les résultats:

```
// Tri par nombre d'amis, du plus grand au plus petit
popularUsers = fetch("users/*/order/friends.size:desc");

// Tri par nom d'utilisateur alphabétique
alphabeticalUsers = fetch("users/*/order/username:asc");
```

#### Limitation des résultats avec 'limit'

Le paramètre `limit` permet de limiter le nombre de résultats:

```
// Récupère uniquement les 5 premiers utilisateurs
fewUsers = fetch("users/*/limit/5");

// Combine tri et limitation: les 10 utilisateurs les plus puissants
eliteUsers = fetch("users/*/order/rank.power:desc/limit/10");
```

### La fonction map

La fonction `map` permet de transformer une collection en une liste formatée. Elle est particulièrement utile pour créer des représentations lisibles des données.

#### Syntaxe complète

```
map("collection[options] format_string")
```

#### Options disponibles

| Option | Description | Valeur par défaut | Exemple |
|--------|-------------|------------------|---------|
| `start` | Index de départ | 0 | `start:1` |
| `reverse` | Inverser l'ordre | false | `reverse:true` |

#### Variables spéciales dans le format

| Variable | Description | Exemple |
|----------|-------------|---------|
| `{index}` | Numéro d'index actuel | `#{index}.` |
| `{current}` | Élément entier | `{current}` |
| `{current.propriété}` | Propriété de l'élément | `{current.username}` |

#### Exemples simples

```java
// Liste basique avec index commençant à 0
userList = map("users #{index}. {current.username}");
// Résultat: ["0. Admin", "1. User1", "2. User2", ...]

// Liste avec index commençant à 1
userList = map("users[start:1] #{index}. {current.username}");
// Résultat: ["1. Admin", "2. User1", "3. User2", ...]

// Liste inversée (derniers éléments en premier)
reverseList = map("users[reverse:true] #{index}. {current.username}");
// Résultat: ["0. UserLast", "1. UserBeforeLast", ...]
```

#### Formatage avancé

```java
// Formatage complexe avec plusieurs propriétés
userDetails = map("users #{index}. {current.username} - Rang: {current.rank.name} (Puissance: {current.rank.power})");
// Résultat: ["0. Admin - Rang: Administrateur (Puissance: 100)", ...]
```

### Opérations conditionnelles avec if

La fonction `if` permet d'exécuter du code de manière conditionnelle.

#### Syntaxe de base

```
if(condition, valeur_si_vrai, valeur_si_faux)
```

#### Types de conditions supportées

| Type | Opérateurs | Exemple |
|------|------------|---------|
| Comparaison numérique | `>`, `<`, `>=`, `<=`, `==`, `!=` | `user.friends.size > 5` |
| Test d'égalité | `==`, `!=` | `user.active == true` |
| Test de null | `== null`, `!= null` | `user.rank != null` |
| Collection | `.size`, `.empty` | `user.friends.size` |
| Booléen | Directement | `user.active` |
| Combinaison | `&&` (ET), `||` (OU) | `user.friends.size > 10 && user.active` |

#### Exemples pratiques

```java
// Condition simple
status = if(user.active, "En ligne", "Hors ligne");

// Condition numérique
popularity = if(user.friends.size > 10, "Populaire", "Nouveau");

// Condition imbriquée
label = if(user.rank.power > 100,
    if(user.friends.size > 10, "Élite", "Étoile montante"),
    "Débutant"
);

// Vérification de valeur null
rankName = if(user.rank != null, user.rank.name, "Sans rang");

// Conditions combinées
status = if(user.friends.size > 10 && user.active, "VIP actif", "Standard");
```

### Concatenation avec concat

La fonction `concat` permet de créer des chaînes de caractères en y insérant des variables.

#### Syntaxe de base

```
concat("texte {variable} texte {autreVariable}")
```

#### Variables dans les chaînes

| Format | Description | Exemple |
|--------|-------------|---------|
| `{variable}` | Insère la valeur d'une variable | `{users.size}` |
| `{variable.propriété}` | Insère la valeur d'une propriété | `{user.username}` |
| `{if(...)}` | Insère le résultat d'une condition | `{if(user.active, "En ligne", "Hors ligne")}` |

#### Exemples simples

```java
// Message simple avec variable
message = concat("Bonjour, {user.username}!");
// Résultat: "Bonjour, Admin!"

// Utilisation avec condition
status = concat("Status: {if(user.active, 'Actif', 'Inactif')}");
// Résultat: "Status: Actif" ou "Status: Inactif"
```

#### Formatage multi-lignes

```java
// Profil utilisateur multi-lignes
profile = concat("""
    Profil de {user.username}
    Rang: {user.rank.name}
    Amis: {user.friends.size}
    Status: {if(user.active, "En ligne", "Hors ligne")}
""");
```

### Manipulation des propriétés

#### Accès direct aux propriétés

Vous pouvez accéder directement aux propriétés des objets en utilisant la notation par point:

```java
// Accès simple
username = user.username;

// Accès imbriqué
rankName = user.rank.name;
rankPower = user.rank.power;
```

#### Propriétés spéciales pour les collections

| Propriété | Description | Exemple |
|-----------|-------------|---------|
| `size` | Nombre d'éléments dans la collection | `users.size` |
| `empty` | Booléen indiquant si la collection est vide | `users.empty` |

```java
// Vérifier si une collection a des éléments
hasUsers = !users.empty;

// Utiliser la taille dans une condition
userCount = users.size;
hasEnoughUsers = if(userCount > 10, true, false);
```

#### Accès par index

```java
// Accès au premier élément (index 0)
firstUser = users[0];

// Accès à un élément spécifique
thirdUser = users[2];
```

## Exemples pratiques

### Système de classement

Créer un classement des joueurs en fonction de leur puissance:

```java
// Script complet pour un système de classement
script = """
    // Récupération des utilisateurs triés par puissance
    users = fetch("users/*/order/rank.power:desc");
    
    // En-tête du classement
    header = concat("🏆 CLASSEMENT DES JOUEURS 🏆");
    
    // Liste des joueurs formatée avec détails
    leaderboard = map("users[start:1] #{index}. {current.username}
        ├─ Rang: {current.rank.name}
        ├─ Puissance: {current.rank.power}
        ├─ Amis: {current.friends.size}
        └─ Status: {if(current.active, "✓ En ligne", "✗ Hors ligne")}
    ");
    
    // Statistiques globales
    totalPlayers = users.size;
    activePlayers = fetch("users/*/active").size;
    stats = concat("""
        📊 Statistiques
        ├─ Total des joueurs: {totalPlayers}
        └─ Joueurs actifs: {activePlayers}
    """);
""";
```

### Profil utilisateur

Créer un profil détaillé pour un utilisateur spécifique:

```java
// Script pour un profil utilisateur détaillé
script = """
    // Récupération de l'utilisateur spécifique
    user = fetch("users/" + userId);
    
    // Vérification que l'utilisateur existe
    exists = user != null;
    
    // Création du profil si l'utilisateur existe
    profile = if(exists,
        concat("""
            👤 {user.username}
            {if(user.rank != null, concat("📊 ", user.rank.name), "🆕 Sans rang")}
            
            📈 Statistiques:
            ├─ Amis: {user.friends.size}
            ├─ Puissance: {if(user.rank != null, user.rank.power, "0")}
            ├─ Status: {if(user.active, "✅ En ligne", "❌ Hors ligne")}
            └─ Membre depuis: {user.createdAt}
        """),
        "❌ Utilisateur non trouvé"
    );
    
    // Liste des amis (si l'utilisateur existe et a des amis)
    friendsList = if(exists && user.friends.size > 0,
        map("user.friends ・{current.username} ({if(current.active, 'En ligne', 'Hors ligne')})"),
        ["Aucun ami"]
    );
""";
```

### Analyse de données

Extraire et analyser des statistiques sur les utilisateurs:

```java
// Script d'analyse des données utilisateurs
script = """
    // Récupération des données de base
    allUsers = fetch("users/*");
    activeUsers = fetch("users/*/active");
    
    // Statistiques générales
    totalUsers = allUsers.size;
    activeCount = activeUsers.size;
    activePercentage = (activeCount * 100) / totalUsers;
    
    // Répartition par rang
    users = fetch("users/*");
    adminCount = 0;
    moderatorCount = 0;
    regularCount = 0;
    
    // Boucle sur les utilisateurs pour compter par rôle
    for (user in users) {
        if (user.rank.name == "Admin") {
            adminCount = adminCount + 1;
        } else if (user.rank.name == "Moderator") {
            moderatorCount = moderatorCount + 1;
        } else {
            regularCount = regularCount + 1;
        }
    }
    
    // Génération du rapport
    report = concat("""
        📊 RAPPORT D'ANALYSE 📊
        
        Utilisateurs:
        ├─ Total: {totalUsers}
        ├─ Actifs: {activeCount} ({activePercentage}%)
        └─ Inactifs: {totalUsers - activeCount} ({100 - activePercentage}%)
        
        Répartition par rang:
        ├─ Admins: {adminCount} ({(adminCount * 100) / totalUsers}%)
        ├─ Modérateurs: {moderatorCount} ({(moderatorCount * 100) / totalUsers}%)
        └─ Réguliers: {regularCount} ({(regularCount * 100) / totalUsers}%)
    """);
""";
```

## Optimisation des performances

### Bonnes pratiques

1. **Limiter les résultats volumineux**:
```java
// Recommandé
users = fetch("users/*/limit/100");
// Éviter pour de grandes collections
users = fetch("users/*");
```

2. **Récupérer uniquement les propriétés nécessaires**:
```java
// Efficace (ne récupère que les noms)
usernames = fetch("users/*/username");
// Moins efficace (récupère toutes les entités)
users = fetch("users/*");
usernames = map("users {current.username}");
```

3. **Utiliser le tri côté serveur**:
```java
// Efficace (tri au niveau du dépôt)
topUsers = fetch("users/*/order/rank.power:desc/limit/10");
// Inefficace (tri manuel)
users = fetch("users/*");
// ... code de tri manuel ...
```

4. **Accéder directement aux propriétés spécifiques**:
```java
// Efficace (ne récupère que la propriété spécifique)
username = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9/username");
// Moins efficace (récupère toute l'entité)
user = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9");
username = user.username;
```

### À éviter

1. **Récupération inutile d'entités complètes**:
```java
// Inefficace - récupère toutes les entités pour un simple comptage
users = fetch("users/*");
count = users.size;

// Meilleure approche
count = fetch("users/*").size;
```

2. **Transformations en chaîne inutiles**:
```java
// Inefficace - transformations multiples
users = fetch("users/*");
names = map("users {current.username}");
formattedNames = map("names User: {current}");

// Meilleure approche - une seule transformation
formattedNames = map("users User: {current.username}");
```

## Dépannage

### Erreurs courantes et solutions

| Erreur | Cause probable | Solution |
|--------|----------------|----------|
| `NullPointerException` | Accès à une propriété d'un objet null | Vérifiez l'existence avec `if(objet != null, ...)` |
| `ClassCastException` | Tentative de conversion incorrecte | Assurez-vous que les types sont compatibles |
| `Chemin invalide` | Format de chemin incorrect | Vérifiez la syntaxe du chemin d'accès |
| `Repository not found` | Dépôt non enregistré | Vérifiez l'enregistrement du dépôt dans Architect |
| `No entity found with ID` | Entité inexistante | Vérifiez que l'ID existe ou gérez le cas null |

### Débogage

Pour faciliter le débogage, vous pouvez stocker des valeurs intermédiaires:

```java
// Stockez et affichez des valeurs intermédiaires
users = fetch("users/*");
userCount = users.size;
message = concat("Nombre d'utilisateurs: {userCount}");
```

## Référence technique

### Types de données supportés

| Type Java | Utilisation dans Anchor | Notes |
|-----------|------------------------|-------|
| String | Texte et identifiants | Utilisé pour les noms, descriptions, etc. |
| Integer, Long | Valeurs numériques | Utilisé pour les compteurs, IDs numériques |
| Boolean | Conditions | true/false pour les états |
| List, Collection | Collections d'objets | Accès par index, taille, etc. |
| Map | Dictionnaires | Accès aux valeurs par clé |
| Custom objects | Entités | Accès aux propriétés avec notation point |

### Opérateurs supportés

| Catégorie | Opérateurs | Exemple |
|-----------|------------|---------|
| Arithmétique | `+`, `-`, `*`, `/` | `count = users.size + 1` |
| Comparaison | `>`, `<`, `>=`, `<=`, `==`, `!=` | `isAdmin = user.role == "ADMIN"` |
| Logique | `&&` (ET), `\|\|` (OU), `!` (NON) | `canEdit = isAdmin \|\| hasPermission` |
| Accès | `.` (propriété), `[]` (index) | `user.name`, `users[0]` |

Cette documentation couvre l'ensemble des fonctionnalités d'Anchor et devrait vous permettre de créer des scripts puissants tout en restant simples et lisibles. N'hésitez pas à explorer les différentes combinaisons de fonctions pour obtenir exactement le comportement dont vous avez besoin. 