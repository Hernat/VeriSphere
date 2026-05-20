# VeriSphere — landing page

Page d'accueil statique HTML/CSS/JS pour [VeriSphere](https://github.com/Hernat/VeriSphere). Réutilise le langage visuel Wispr Flow de l'Epic 8 (palette crème + sauge, EB Garamond + Figtree) et met en avant le pipeline triple-vérification (Gemini + SerpAPI + Gemini reverdict) shippé en v0.2.1.

> Le dossier s'appelle `docs/` (pas `landing/`) parce que GitHub Pages "Deploy from a branch" n'accepte que `/ (root)` ou `/docs` comme dossier de publication — c'est une contrainte du provider, pas un choix sémantique.

## Aperçu local

Aucun build step, aucune dépendance npm. Deux façons d'ouvrir :

**A. Direct (file://)** — double-clic sur `index.html`. Fonctionne sur Chrome / Firefox / Edge récents. Une seule limite : le sprite SVG est injecté via `fetch()` ce qui peut être bloqué par CORS sur certains navigateurs en file:// ; dans ce cas les icônes ne s'affichent pas mais le reste reste opérationnel.

**B. Serveur local (recommandé)** :

```powershell
cd D:\Projets\Gemini-Verif\docs
python -m http.server 8000
# puis ouvre http://localhost:8000
```

## Structure

```
docs/
├── index.html                # page unique, FR, 8 sections sémantiques
├── styles.css                # tokens Wispr Flow + responsive + dark mode
├── script.js                 # theme toggle, smooth scroll, scroll-reveal, sprite inline
├── assets/
│   ├── logo_vs.png           # copié depuis app/src/main/res/drawable-nodpi/
│   ├── favicon.svg           # glyph "◐" sauge sur crème
│   ├── icons.svg             # sprite Lucide-style monochrome
│   └── screenshots/          # captures Android réelles (1280×2856)
│       ├── bull-flottant.png        # bulle sur l'écran d'accueil (HERO)
│       ├── bull-veille.png          # bulle en veille (section "En action")
│       ├── bull-chargement.png      # bulle en cours de vérification
│       ├── bull-verdict-tooltip.png # bulle + flash verdict VRAI
│       ├── listes-verdict.png       # écran Vérifications récentes avec tabs
│       ├── details-verdict.png      # panneau détail avec sources
│       ├── parametre-page.png       # écran Paramètres · clés API BYOK
│       └── historique-page.png      # historique en panel d'aperçu (overlay)
└── README.md                 # ce fichier
```

### Captures d'écran

Les 8 PNG dans `assets/screenshots/` sont les captures Android réelles de l'app (capturées via AVD, ratio Pixel ~1280×2856). Elles incluent déjà le bezel/notch du device — pas besoin d'un wrapper `.phone` CSS supplémentaire. Le CSS `.phone-img` applique seulement un `border-radius` + shadow + `aspect-ratio` pour préserver les proportions.

Pour les régénérer après une mise à jour de l'UI :
```bash
# Avec l'AVD démarré et l'app installée :
adb -s emulator-5554 exec-out screencap -p > docs/assets/screenshots/bull-flottant.png
# Répéter pour chaque écran à capturer
```

## Tokens design

Les tokens CSS proviennent verbatim de `_bmad-output/planning-artifacts/epic-8-wispr-flow-ui-preview.html` (single source of truth pour la palette Wispr Flow VeriSphere) :

- **Light** — canvas #F8F4EA · paper #FBF8F0 · ink #2A2620 · sage #7BA889 · lavender #C8BFE0 · pulse gold #C9A961
- **Dark** — canvas #1A1612 · paper #221E18 · ink #F0EADC · sage #9CC2A8 · lavender #B5ABCE
- **Verdict softs** — VRAI sage tint · FAUX coral · DOUTEUX sand · NON VÉRIFIABLE lavender

Typographies via Google Fonts (preconnect inclus dans `<head>`) :
- **EB Garamond** (titres éditoriaux)
- **Figtree** (corps + UI)

## Accessibilité

- Tokens validés pour contraste WCAG AA dans light + dark
- Focus rings visibles sur tous les éléments interactifs (`:focus-visible`)
- `prefers-reduced-motion: reduce` respecté — animations désactivées
- `prefers-color-scheme: dark` auto-appliqué si aucun choix utilisateur stocké
- Skip link vers `#main` (sr-only)
- Semantic HTML5 (`<header>`, `<nav>`, `<main>`, `<section>`, `<footer>`)
- ARIA labels sur tous les boutons icon-only
- FAQ utilise `<details>/<summary>` natif (a11y-free)

## Tests rapides

| Vérification | Comment |
|---|---|
| Responsive | DevTools → 375 / 768 / 1024 / 1440 px, pas de scroll horizontal |
| Dark mode | Toggle topbar + refresh — la pref persiste via localStorage |
| Lighthouse | Cible : a11y ≥ 95, perf ≥ 90, best-practices ≥ 95 |
| Reduced motion | DevTools → Rendering → Emulate `prefers-reduced-motion: reduce` |
| Keyboard | `Tab` parcourt logiquement la page, focus visible partout |

## Déployer sur GitHub Pages

1. Va sur https://github.com/Hernat/VeriSphere/settings/pages
2. **Source** : `Deploy from a branch`
3. **Branch** : `main` + **Folder** : `/docs`
4. Clique **Save**
5. URL finale : `https://hernat.github.io/VeriSphere/`

Le re-déploiement est automatique à chaque push sur `main` qui modifie `docs/`.

## Crédits

Inspiration visuelle : [Wispr Flow](https://wisprflow.ai/). Reprise des tokens éditoriaux + Garamond/Figtree pairing.

Logo VeriSphere : Hernat. Icônes : style [Lucide](https://lucide.dev/) (réécrites inline, pas de CDN).
