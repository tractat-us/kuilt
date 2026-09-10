import json, subprocess, sys

Q = """
query($cursor: String) {
  repository(owner: "tractat-us", name: "kuilt") {
    issues(first: 60, orderBy: {field: CREATED_AT, direction: DESC}, after: $cursor, states: [OPEN, CLOSED]) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number title state createdAt closedAt
        body
        labels(first: 10) { nodes { name } }
        comments(last: 1) { nodes { body createdAt author { login } } }
        timelineItems(last: 10, itemTypes: [CLOSED_EVENT, CROSS_REFERENCED_EVENT]) {
          nodes {
            __typename
            ... on ClosedEvent { closer { __typename ... on PullRequest { number title } } }
          }
        }
      }
    }
  }
}
"""
out = []
cursor = None
while True:
    args = ["gh","api","graphql","-f","query="+Q]
    if cursor: args += ["-F","cursor="+cursor]
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        print("ERR", r.stderr[:500], file=sys.stderr); break
    j = json.loads(r.stdout)["data"]["repository"]["issues"]
    for n in j["nodes"]:
        n["body"] = (n.get("body") or "")[:1800]
        cs = n["comments"]["nodes"]
        n["lastComment"] = (cs[0]["body"][:400] if cs else "")
        n["labels"] = [l["name"] for l in n["labels"]["nodes"]]
        closer=None
        for t in n["timelineItems"]["nodes"]:
            if t.get("__typename")=="ClosedEvent" and t.get("closer") and t["closer"].get("number"):
                closer=t["closer"]["number"]
        n["closerPR"]=closer
        del n["comments"]; del n["timelineItems"]
        out.append(n)
    if not j["pageInfo"]["hasNextPage"]: break
    cursor = j["pageInfo"]["endCursor"]
    if min(x["number"] for x in out) < 1700: break
json.dump(out, open("bodies.json","w"))
print("fetched", len(out), "min", min(x["number"] for x in out))
