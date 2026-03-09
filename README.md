## what
- **corelife** — core gameplay systems
- **moralityengine** — morality and player alignment system
- **zonespawn** — zone-based spawn management

## how

### Dev
any branch starting with `dev` will automatically build and deploy to the dev server on every push

config files at `plugins/*/src/main/resources/config.yml` for each plugin is automatically overwritten on the dev server with the version from the repo on every build so the dev server always reflects the latest config

dev server and ip in cord

### Prod
prod is deployed manually through an action

prod configs aren't automatically overwritten i'll just do that manually

## Repository Structure

```
neurofrostia/
├── .github/
│   └── workflows/
│       ├── deploy-dev.yml      ← auto triggers on dev* branches
│       └── deploy-prod.yml     ← manual trigger only
└── plugins/
    ├── corelife/
    │   ├── src/
    │   │   └── main/resources/
    │   │       └── config.yml  ← auto deployed to dev on build
    │   └── pom.xml
    ├── moralityengine/
    │   ├── src/
    │   │   └── main/resources/
    │   │       └── config.yml  ← auto deployed to dev on build
    │   └── pom.xml
    ├── zonespawn/
    │   ├── src/
    │   └── pom.xml
    └── pom.xml
```

---

## Build Order

moralityengine depends on corelife so they build in this order:

1. parent pom installed
2. and install corelife
3. build moralityengine