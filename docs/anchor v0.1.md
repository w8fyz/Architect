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

#### Conditions dans le format

Vous pouvez maintenant utiliser des conditions `if` directement dans le format :

```java
// Format avec condition simple
userList = map("users #{index}. {current.username} if(current.active, '(en ligne)', '(hors ligne)')");
// Résultat: ["0. Admin (en ligne)", "1. User1 (hors ligne)", ...]

// Format avec conditions multiples
userStatus = map("users #{index}. {current.username} if(current.active, '✓', '✗') if(current.friends.size > 5, ' (populaire)', '')");
// Résultat: ["0. Admin ✓ (populaire)", "1. User1 ✗", ...]
```

Syntaxe des conditions dans map:
```
if(condition, valeur_si_vrai, valeur_si_faux)
```

### Accès aux éléments d'une liste

Vous pouvez maintenant accéder directement aux éléments d'une liste en utilisant l'index entre crochets :

```java
// Accès au premier élément
firstUser = users[0];

// Accès au dernier élément
lastUser = users[users.size - 1];

// Utilisation de l'élément
username = firstUser.username;
```

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
// Formatage avec plusieurs propriétés
userDetails = map("users #{index}. {current.username} - Rang: {current.rank.name}");
// Résultat: ["0. Admin - Rang: Administrateur", ...]

// Formatage multi-lignes
userProfiles = map("users[start:1] == Utilisateur #{index} ==
Nom: {current.username}
Rang: {current.rank.name}
Amis: {current.friends.size}
");
// Résultat: ["== Utilisateur 1 ==\nNom: Admin\nRang: Administrateur\nAmis: 5", ...]
```

⚠️ **Limitations importantes**:
- La fonction `map` ne supporte pas l'utilisation de conditions (`if`) dans son format string
- Seules les variables `{index}`, `{current}` et `{current.property}` sont supportées

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
| Collection | `.size` | `user.friends.size` |
| Booléen | Directement | `user.active` |
| Négation | `!` | `!user.active` |

#### Exemples de conditions

```java
// Condition simple
status = if(user.active, "En ligne", "Hors ligne");

// Condition avec NOT
isOffline = if(!user.active, "Hors ligne", "En ligne");

hasNoFriends = if(user.friends.size == 0, "Pas d'amis", "A des amis");

// Vérification de null avec NOT
noRank = if(user.rank == null, "Sans rang", user.rank.name);
```

### Manipulation des collections

Les collections dans Anchor peuvent être manipulées de plusieurs façons :

#### Accès aux propriétés des collections

| Propriété | Description | Exemple |
|-----------|-------------|---------|
| `size` | Nombre d'éléments dans la collection | `users.size` |


⚠️ **Limitations importantes**:
- Les collections ne peuvent être manipulées que via `fetch` et `map`

#### Exemples valides

```java
// Récupérer la taille d'une collection
userCount = users.size;

// Transformer une collection
userList = map("users #{index}. {current.username}");

// Trier et limiter une collection
topUsers = fetch("users/*/order/rank.power:desc/limit/5");
```

# Documentation AnchorScript

## Introduction
AnchorScript est un langage de script puissant conçu pour manipuler et interroger des données de manière simple et intuitive.

## Fonctions de base

### fetch
Récupère des données depuis un repository.

```java
// Récupérer tous les utilisateurs
users = fetch("users/*");

// Récupérer un utilisateur spécifique par ID
user = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9");

// Récupérer les utilisateurs triés par nombre d'amis (descendant) avec une limite
users = fetch("users/*/order/friends.size:desc/limit/5");
```

### map
Transforme une collection en appliquant un format à chaque élément.

```java
// Format simple avec numérotation
userList = map("users #{index}. {current.username}");

// Format avec condition sur le nombre d'amis
userList = map("users #{index}. {current.username} ({current.friends.size} friends)");

// Format avec condition et ordre inversé
userList = map("users[reverse:true] #{index}. {current.username}");
```

### if
Évalue une condition et retourne une valeur en fonction du résultat.

```java
// Condition simple
status = if(user.active, "Actif", "Inactif");

// Condition inversée avec l'opérateur !
status = if(!user.active, "Inactif", "Actif");

// Condition sur le nombre d'amis
status = if(user.friends.size > 5, "Populaire", "Nouveau");

// Condition inversée sur une collection
hasNoFriends = if(!user.friends, "Pas d'amis", "A des amis");
```

## Exemples complets

### 1. Affichage des utilisateurs avec leur statut
```java
// Récupérer tous les utilisateurs
users = fetch("users/*");

// Créer une liste formatée avec statut d'activité
userStatus = map("users #{index}. {current.username} status : {current.status}");
```

### 2. Liste des utilisateurs populaires
```java
// Récupérer les utilisateurs triés par nombre d'amis
users = fetch("users/*/order/friends.size:desc/limit/3");

// Afficher les utilisateurs avec leur rang
topUsers = map("users #{index}. {current.username} - Rank: {current.rank.name}");
```

### 3. Manipulation de variables et conditions
```java
// Récupérer les utilisateurs
users = fetch("users/*");

// Accéder au premier utilisateur
firstUser = users[0];

// Extraire le nom d'utilisateur
username = firstUser.username;

// Formater avec concat
formatted = concat("User: {username}");
```

### 4. Opérations sur les collections
```java
// Récupérer les utilisateurs
users = fetch("users/*");

// Compter le nombre d'utilisateurs
count = users.size;

// Vérifier si la collection n'est pas vide
hasUsers = count > 0;
```

### 5. Conditions complexes
```java
// Récupérer un utilisateur
user = fetch("users/67047805-2dac-42d5-b4a1-18dfcc9759d9");

// Vérifier plusieurs conditions
isNotActive = !user.active;
hasNoFriends = !(user.friends.size > 0);

// Créer une liste avec conditions multiples
userList = map("users #{index}. {current.username} if(current.active, '✓', '✗') if(current.friends.size > 5, ' (popular)', '')");
```

## Opérateurs

### Opérateur de négation (!)
L'opérateur `!` peut être utilisé pour inverser une condition :

```java
// Dans une fonction if
status = if(!user.active, "Inactif", "Actif");
status = if(!(user.friends.size > 0), "Pas d'amis", "A des amis");

// Dans une comparaison
isNotActive = !user.active;
hasNoFriends = !user.friends;
```

### Opérateurs de comparaison
- `>` : supérieur à
- `<` : inférieur à
- `==` : égal à
- `!=` : différent de
- `>=` : supérieur ou égal à
- `<=` : inférieur ou égal à

```java
// Exemples
hasManyFriends = user.friends.size > 10;
isNewUser = user.level < 5;
isAdmin = user.role == "admin";
```

## Bonnes pratiques

1. Utilisez des noms de variables descriptifs
2. Préférez les conditions simples aux conditions complexes
3. Utilisez le formatage approprié pour une meilleure lisibilité
4. Commentez votre code pour expliquer les opérations complexes
5. Vérifiez toujours les valeurs null avant d'accéder aux propriétés

## Notes importantes

- Les collections commencent à l'index 0
- Les conditions dans `map` et `if` peuvent utiliser l'opérateur `!`
- Les propriétés des objets sont accessibles avec la notation point (`.`)
- La propriété `size` est disponible pour toutes les collections 