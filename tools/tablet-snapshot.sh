#!/bin/sh
# Sauvegarde / restauration de la configuration d'une tablette TileHouse avant et après des tests
# (mode démo, captures d'écran, changement de langue ou de skin).
#
#   tools/tablet-snapshot.sh save    http://TABLETTE:8080 fichier.json
#   tools/tablet-snapshot.sh restore http://TABLETTE:8080 fichier.json
#
# La copie ne contient aucun secret (jeton, mots de passe, clé Immich) : ils restent sur la tablette.
# « restore » quitte d'abord le mode démo, remet la copie, puis vérifie que la tablette correspond à la copie.
set -eu
cmd=${1:?save|restore}; url=${2:?adresse de la tablette}; file=${3:?fichier}
H='Content-Type: application/json'
case "$cmd" in
  save)
    demo=$(curl -fsS "$url/api/state" | python3 -c 'import json,sys;print(json.load(sys.stdin)["demo"])')
    [ "$demo" = "False" ] || { echo "La tablette est en mode démo : quittez-le avant de sauvegarder." >&2; exit 1; }
    curl -fsS -X POST -H "$H" -d '{"secrets":false}' "$url/api/export" > "$file"
    python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));assert d.get("format")=="homepanel-config";print("Copie enregistrée :",len(d.get("rooms",[])),"pièces")' "$file"
    ;;
  restore)
    curl -fsS -X POST -H "$H" -d '{"on":false}' "$url/api/demo" > /dev/null
    python3 -c 'import json,sys;print(json.dumps({"config":json.load(open(sys.argv[1]))}))' "$file" | curl -fsS -X POST -H "$H" --data-binary @- "$url/api/restore" > /dev/null
    sleep 2
    curl -fsS -X POST -H "$H" -d '{"secrets":false}' "$url/api/export" | python3 -c '
import json,sys
now=json.load(sys.stdin); want=json.load(open(sys.argv[1]))
diff=[k for k in want if k not in ("exportedAt","version") and json.dumps(want[k],sort_keys=True)!=json.dumps(now.get(k),sort_keys=True)]
print("Restauration vérifiée : identique à la copie." if not diff else "DIFFÉRENCES : "+", ".join(diff)); sys.exit(1 if diff else 0)' "$file"
    ;;
  *) echo "save|restore" >&2; exit 2;;
esac
