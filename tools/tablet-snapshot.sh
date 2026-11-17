#!/bin/sh
# Sauvegarde / restauration de la configuration d'une tablette TileHouse avant et après des tests
# (mode démo, captures d'écran, changement de langue ou de skin).
#
#   tools/tablet-snapshot.sh save    http://TABLETTE:8080 fichier.json
#   tools/tablet-snapshot.sh restore http://TABLETTE:8080 fichier.json
#   tools/tablet-snapshot.sh diff    http://TABLETTE:8080 fichier.json
#
# La copie ne contient aucun secret (jeton, mots de passe, clé Immich) : ils restent sur la tablette.
# « restore » quitte d'abord le mode démo, remet la copie, puis vérifie que la tablette correspond à la copie.
# « diff » montre ce qui a changé depuis la copie, sans rien modifier. À lancer avant « restore » sur une tablette
# en service : si quelqu'un a changé un réglage entre-temps, « restore » l'écraserait.
# Prenez la copie APRÈS avoir installé la version à tester : un réglage ajouté par une nouvelle version
# n'est pas dans une copie plus ancienne (le script le signale).
set -eu
cmd=${1:?save|restore|diff}; url=${2:?adresse de la tablette}; file=${3:?fichier}
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
same=lambda a,b: json.dumps(a,sort_keys=True)==json.dumps(b,sort_keys=True)
diff=[]; extra=[]
for k,v in want.items():
    if k in ("exportedAt","version"): continue
    if isinstance(v,dict) and isinstance(now.get(k),dict):
        diff+=[k+"."+kk for kk,vv in v.items() if not same(vv,now[k].get(kk))]
        extra+=[k+"."+kk for kk in now[k] if kk not in v]
    elif not same(v,now.get(k)): diff.append(k)
if extra: print("ATTENTION : réglages absents de la copie (app mise à jour depuis ?), non restaurés : "+", ".join(extra))
print("Restauration vérifiée : identique à la copie." if not diff else "DIFFÉRENCES : "+", ".join(diff)); sys.exit(1 if diff or extra else 0)' "$file"
    ;;
  diff)
    curl -fsS -X POST -H "$H" -d '{"secrets":false}' "$url/api/export" | python3 -c '
import json,sys
now=json.load(sys.stdin); was=json.load(open(sys.argv[1]))
same=lambda a,b: json.dumps(a,sort_keys=True)==json.dumps(b,sort_keys=True)
diff=[]
for k in sorted(set(was)|set(now)):
    if k in ("exportedAt","version"): continue
    a,b=was.get(k),now.get(k)
    if isinstance(a,dict) and isinstance(b,dict): diff+=[k+"."+kk for kk in sorted(set(a)|set(b)) if not same(a.get(kk),b.get(kk))]
    elif not same(a,b): diff.append(k)
print("Aucun changement depuis la copie." if not diff else "Changé depuis la copie : "+", ".join(diff)); sys.exit(1 if diff else 0)' "$file"
    ;;
  *) echo "save|restore|diff" >&2; exit 2;;
esac
