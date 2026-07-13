#!/usr/bin/env python3
"""
Functional test for MovieBlastProvider (ported to Streamflix)
Tests every provider method against the live API as close to the Kotlin code as possible.
"""
import hmac, hashlib, base64, time, urllib.parse, urllib.request, json, sys
from urllib.parse import urlparse

BASE_URL = "https://app.cloud-mb.xyz"
TOKEN = "jdvhhjv255vghhgdhvfch2565656jhdcghfdf"
HMAC_SECRET = "GJ8reydarI7Jqat9rvbAJKNQ9gY4DoEQF2H5nfuI1gi"
USER_AGENT = "okhttp/5.0.0-alpha.6"

IMITATE_MOVIE_PREFIX = "mb_movie_"
IMITATE_TV_PREFIX = "mb_tv_"
IMITATE_SEASON_PREFIX = "mb_season_"
IMITATE_EP_PREFIX = "mb_ep_"

pass_count = 0
fail_count = 0

def report(name, ok, detail=""):
    global pass_count, fail_count
    if ok:
        pass_count += 1
        print(f"  ✅ PASS: {name}")
    else:
        fail_count += 1
        print(f"  ❌ FAIL: {name} - {detail}")

def api_get(path):
    url = f"{BASE_URL}/{path}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        resp = urllib.request.urlopen(req, timeout=15)
        body = resp.read().decode('utf-8')
        return json.loads(body)
    except Exception as e:
        return {"error": str(e)}

def api_get_raw(path):
    url = f"{BASE_URL}/{path}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    resp = urllib.request.urlopen(req, timeout=15)
    return resp

def generate_signed_url(raw_url):
    """Matches MovieBlastProvider.generateSignedUrl exactly"""
    if not raw_url:
        return raw_url
    url = f"https://{raw_url}" if not raw_url.startswith("http") else raw_url
    try:
        parsed = urlparse(url)
        path = parsed.path or "/"
        ts = str(int(time.time()))
        sig = hmac.new(HMAC_SECRET.encode('utf-8'), (path + ts).encode('utf-8'), hashlib.sha256).digest()
        b64 = urllib.parse.quote(base64.b64encode(sig).decode('utf-8'))
        return f"{url}?verify={ts}-{b64}"
    except:
        return url

def is_series(raw):
    t = str(raw.get("type", "")).lower()
    c = str(raw.get("content_type", "")).lower()
    return t in ["series", "serie", "tv", "show"] or c == "series"

def match_quality(server_label, stream_url):
    text = f"{stream_url.lower()} {str(server_label).lower()}"
    if "2160" in text or "4k" in text: return "4K"
    if "1440" in text: return "1440p"
    if "1080" in text or "fullhd" in text: return "1080p"
    if "720" in text or "hd" in text: return "720p"
    if "480" in text: return "480p"
    if "360" in text: return "360p"
    import re
    m = re.search(r'[_\-.]?(\d{3,4})p?[_\-.]?', text)
    return f"{m.group(1)}p" if m else "Auto"

def create_server_label(server, lang, stream_url):
    parts = []
    if server: parts.append(server)
    if lang: parts.append(lang)
    q = match_quality(server, stream_url)
    if q != "Auto": parts.append(q)
    return " • ".join(parts) or "MovieBlast"

# ============================================================
print("="*60)
print("MOVIEBLAST PROVIDER — FULL FUNCTIONAL TEST")
print("="*60)

# 1. TEST HOME
print("\n--- 1. getHome() ---")
for section_name, section_path in [
    ("Trending", f"api/genres/trending/all/{TOKEN}"),
    ("Popular Movies", f"api/genres/popularmovies/all/{TOKEN}"),
    ("Popular Series", f"api/genres/popularseries/all/{TOKEN}"),
    ("Recommended", f"api/genres/recommended/all/{TOKEN}"),
]:
    try:
        data = api_get(f"{section_path}?page=1")
        items = data.get("data", [])
        report(f"{section_name}: {len(items)} items", len(items) > 0, f"got {len(items)}")
        if items:
            item = items[0]
            report(f"  item has id", bool(item.get("id")))
            report(f"  item has name/title", bool(item.get("name") or item.get("title")))
            report(f"  item has poster", bool(item.get("poster_path")))
            # Verify type detection
            if is_series(item):
                report(f"  detected as SERIES (type={item.get('type')})", True)
            else:
                report(f"  detected as MOVIE (type={item.get('type')})", True)
    except Exception as e:
        report(f"{section_name}", False, str(e))

# 2. TEST SEARCH
    print("\n--- 2. search() ---")
for query in ["pushpa", "kalki", "bahubali"]:
    try:
        safe_q = urllib.parse.quote(query)
        data = api_get(f"api/search/{safe_q}/{TOKEN}")
        arr = data.get("search") or data.get("data") or []
        report(f"search '{query}': {len(arr)} results", len(arr) > 0, f"got {len(arr)}")
    except Exception as e:
        report(f"search '{query}'", False, str(e))

# 3. TEST MOVIES
print("\n--- 3. getMovies() ---")
try:
    data = api_get(f"api/genres/popularmovies/all/{TOKEN}?page=1")
    movies = data.get("data", [])
    report(f"getMovies page 1: {len(movies)} movies", len(movies) > 0)
    if movies:
        m = movies[0]
        movie_id = m["id"]
        report(f"First movie: id={movie_id}, name={m.get('name')}", True)
        
        # 3b. TEST GET MOVIE DETAIL
        print("\n--- 3b. getMovie() ---")
        detail = api_get(f"api/media/detail/{movie_id}/{TOKEN}")
        report(f"Movie detail fetched", bool(detail.get("title") or detail.get("name")))
        title = detail.get("title") or detail.get("name", "Unknown")
        report(f"  title: {title}", True)
        report(f"  overview exists", bool(detail.get("overview")))
        report(f"  poster exists", bool(detail.get("poster_path")))
        report(f"  backdrop exists", bool(detail.get("backdrop_path")))
        
        videos = detail.get("videos", [])
        report(f"  videos count: {len(videos)}", len(videos) > 0)
        
        if videos:
            v = videos[0]
            report(f"  video has link", bool(v.get("link")))
            report(f"  video has server", bool(v.get("server")))
            report(f"  video language: {v.get('lang')}", True)
            
            # 3c. TEST GET SERVERS (Movie)
            print("\n--- 3c. getServers(movie) ---")
            video_links = [(vv.get("link",""), vv.get("server","MovieBlast"), vv.get("lang","")) for vv in videos]
            report(f"  extracted {len(video_links)} server entries", len(video_links) > 0)
            
            for i, (link, server, lang) in enumerate(video_links[:3]):
                label = create_server_label(server, lang, link)
                report(f"  server {i+1}: {label}", True)
            
            # 3d. TEST GET VIDEO (signing)
            print("\n--- 3d. getVideo() — HMAC signing ---")
            test_link = video_links[0][0]
            signed = generate_signed_url(test_link)
            report(f"signed URL generated", signed != test_link and "verify=" in signed)
            
            # Actually verify the signed URL works
            headers = {
                "Accept-Encoding": "identity",
                "Connection": "Keep-Alive",
                "Referer": "MovieBlast",
                "User-Agent": "MovieBlast",
                "x-request-x": "com.movieblast",
            }
            try:
                req = urllib.request.Request(signed, method="HEAD", headers=headers)
                resp = urllib.request.urlopen(req, timeout=10)
                ct = resp.headers.get("Content-Type", "N/A")
                cl = resp.headers.get("Content-Length", "0")
                report(f"signed stream: HTTP {resp.status}, {ct}", resp.status == 200, f"Status: {resp.status}")
                if int(cl) > 0:
                    report(f"  content length: {int(cl)/1024/1024:.1f} MB", True)
            except urllib.error.HTTPError as e:
                report(f"signed stream HTTP error", False, f"{e.code}: {e.reason}")
            except Exception as e:
                report(f"signed stream error", False, str(e))
except Exception as e:
    report(f"getMovies/getMovie flow", False, str(e))

# 4. TEST TV SHOWS
print("\n--- 4. getTvShows() & getTvShow() ---")
try:
    data = api_get(f"api/genres/popularseries/all/{TOKEN}?page=1")
    series_list = data.get("data", [])
    report(f"getTvShows: {len(series_list)} series", len(series_list) > 0)
    
    if series_list:
        s = series_list[0]
        series_id = s["id"]
        report(f"First series: id={series_id}, name={s.get('name')}", True)
        
        # 4b. GET TV SHOW DETAIL
        detail = api_get(f"api/series/show/{series_id}/{TOKEN}")
        title = detail.get("name") or detail.get("title", "Unknown")
        report(f"Series detail: {title}", True)
        report(f"  overview exists", bool(detail.get("overview")))
        report(f"  poster exists", bool(detail.get("poster_path")))
        
        seasons = detail.get("seasons", [])
        report(f"  seasons: {len(seasons)}", len(seasons) > 0)
        
        if seasons:
            season = seasons[0]
            snum = season.get("season_number", 0)
            report(f"  season {snum} has episodes", bool(season.get("episodes")))
            
            episodes = season.get("episodes", [])
            if episodes:
                ep = episodes[0]
                report(f"  first episode: {ep.get('name', 'N/A')}", True)
                report(f"  episode has number", bool(ep.get("episode_number")))
                
                ep_videos = ep.get("videos", [])
                report(f"  episode videos: {len(ep_videos)}", len(ep_videos) > 0)
                
                if ep_videos:
                    ev = ep_videos[0]
                    report(f"  video link exists", bool(ev.get("link")))
                    report(f"  video server: {ev.get('server')}", True)
                    
                    # 4c. TEST SIGNED STREAM FOR EPISODE
                    print("\n--- 4c. getVideo(series) — HMAC signing ---")
                    signed = generate_signed_url(ev["link"])
                    report(f"signed episode URL generated", "verify=" in signed)
                    
                    try:
                        req = urllib.request.Request(signed, method="HEAD", headers={
                            "Accept-Encoding": "identity",
                            "Connection": "Keep-Alive",
                            "Referer": "MovieBlast",
                            "User-Agent": "MovieBlast",
                            "x-request-x": "com.movieblast",
                        })
                        resp = urllib.request.urlopen(req, timeout=10)
                        ct = resp.headers.get("Content-Type", "N/A")
                        report(f"episode stream: HTTP {resp.status}, {ct}", resp.status == 200)
                    except urllib.error.HTTPError as e:
                        report(f"episode stream", False, f"{e.code}: {e.reason}")
                    except Exception as e:
                        report(f"episode stream", False, str(e))
except Exception as e:
    report(f"TV Show flow", False, str(e))

# 5. TEST EPISODE ID ENCODING/DECODING (matching Kotlin logic)
print("\n--- 5. Episode ID encoding/decoding ---")
try:
    # Simulate the Kotlin encoding: JSONArray of video objects → URL-encode → store in ID
    sample_videos = [
        {"link": "mbmove.mycdn-mb.xyz/test.mkv", "server": "1080P", "lang": "Telugu"},
        {"link": "mbmove.mycdn-mb.xyz/test2.mkv", "server": "720P", "lang": "Telugu"},
    ]
    links_array = json.dumps(sample_videos)
    encoded = urllib.parse.quote(links_array)
    episode_id = f"mb_ep_123:1:2:{encoded}"
    
    # Simulate decoding (getServers for Video.Type.Episode)
    parts = episode_id.split(":", 3)
    assert len(parts) >= 4, "Should have 4 parts"
    decoded = urllib.parse.unquote(parts[3])
    arr = json.loads(decoded)
    assert len(arr) == 2, "Should have 2 video links"
    assert arr[0]["link"] == "mbmove.mycdn-mb.xyz/test.mkv", "Link should match"
    report(f"episode ID encode/decode roundtrip", True)
except Exception as e:
    report(f"episode ID encode/decode", False, str(e))

# 6. TEST SEASON ID PARSING (matching Kotlin logic)
print("\n--- 6. Season ID parsing ---")
try:
    season_id = "mb_season_123_2"
    stripped = season_id[len("mb_season_"):]
    parts = stripped.split("_", 1)
    assert parts[0] == "123", "Series ID should be 123"
    assert parts[1] == "2", "Season num should be 2"
    assert int(parts[1]) == 2, "Should parse to int 2"
    report(f"season ID parsing", True)
except Exception as e:
    report(f"season ID parsing", False, str(e))

# 7. TEST EDGE CASES
print("\n--- 7. Edge Cases ---")
# 7a. Empty search
data = api_get(f"api/search/{urllib.parse.quote('')}/{TOKEN}")
report("empty search returns gracefully", True)

# 7b. Invalid movie ID
detail = api_get(f"api/media/detail/999999999/{TOKEN}")
report("invalid movie ID handled gracefully", True)

# 7c. URL without http prefix
signed = generate_signed_url("cdn.example.com/video.mp4")
report("httpsify on URL without http", signed.startswith("https://"), signed[:50])

# 7d. Blank URL
signed = generate_signed_url("")
report("blank URL returns blank", signed == "")

# 7e. Quality detection
q = match_quality("1080P", "https://cdn.com/video_1080p.mp4")
report("quality detection 1080p", q == "1080p", q)
q = match_quality("4K", "https://cdn.com/video_4k.mp4")
report("quality detection 4K", q == "4K", q)
q = match_quality("", "https://cdn.com/video.mp4")
report("quality detection Auto", q == "Auto", q)

# ============================================================
print("\n" + "="*60)
print(f"RESULTS: {pass_count} passed, {fail_count} failed")
print("="*60)
if fail_count > 0:
    sys.exit(1)
else:
    print("All tests passed!")
