#!/usr/bin/env python3
"""Upload the release APK to DogeCloud OSS using AccessToken + S3 temporary credentials."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request

try:
    import boto3
    from botocore.config import Config
except ImportError:
    sys.exit("需要 boto3，请先 pip install boto3")


API_HOST = "https://api.dogecloud.com"


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def doge_api(path: str, payload: dict, access_key: str, secret_key: str) -> dict:
    body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    sign_str = f"{path}\n{body}"
    sign = hmac.new(secret_key.encode("utf-8"), sign_str.encode("utf-8"), hashlib.sha1).hexdigest()
    request = urllib.request.Request(
        API_HOST + path,
        data=body.encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"TOKEN {access_key}:{sign}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"多吉云 API 失败（{error.code}）：{raw[:500]}") from error
    data = json.loads(raw)
    if data.get("code") != 200:
        raise SystemExit(f"多吉云 API 失败：{data.get('msg') or raw[:500]}")
    return data.get("data") or {}


def auth_query(path: str, key: str) -> str:
    timestamp = int(time.time()) + 10 * 365 * 24 * 3600
    rand = secrets.token_hex(16)
    uid = "0"
    digest = hashlib.md5(f"{path}-{timestamp}-{rand}-{uid}-{key}".encode("utf-8")).hexdigest()
    return f"{timestamp}-{rand}-{uid}-{digest}"


def s3_config() -> Config:
    kwargs = {"s3": {"addressing_style": "virtual"}}
    try:
        return Config(
            **kwargs,
            request_checksum_calculation="when_required",
            response_checksum_validation="when_required",
        )
    except TypeError:
        return Config(**kwargs)


def main() -> None:
    access_key = env("DOGECLOUD_ACCESS_KEY")
    secret_key = env("DOGECLOUD_SECRET_KEY")
    bucket = env("DOGECLOUD_BUCKET")
    directory = (env("DOGECLOUD_DIRECTORY") or "sysukcb").strip("/")
    if directory == ".":
        directory = ""
    domain = env("DOGECLOUD_DOMAIN").removeprefix("https://").removeprefix("http://").strip("/")
    cdn_key = env("DOGECLOUD_AUTH_KEY")
    version = env("VERSION_NAME")
    apk_path = env("APK_PATH")
    if not access_key or not secret_key or not bucket:
        print("未配置 DOGECLOUD_ACCESS_KEY / DOGECLOUD_SECRET_KEY / DOGECLOUD_BUCKET，跳过对象存储上传。")
        return
    if not version or not apk_path or not os.path.isfile(apk_path):
        raise SystemExit("缺少 VERSION_NAME 或 APK_PATH")

    filename = f"sysukcb-{version}.apk"
    key = f"{directory}/{filename}" if directory else filename
    token = doge_api(
        "/auth/tmp_token.json",
        {"channel": "OSS_UPLOAD", "scopes": [f"{bucket}:{key}"]},
        access_key,
        secret_key,
    )
    credentials = token.get("Credentials") or {}
    buckets = token.get("Buckets") or []
    if not credentials or not buckets:
        raise SystemExit(f"临时密钥响应不完整：{json.dumps(token)[:500]}")

    client = boto3.client(
        "s3",
        region_name="automatic",
        aws_access_key_id=credentials["accessKeyId"],
        aws_secret_access_key=credentials["secretAccessKey"],
        aws_session_token=credentials["sessionToken"],
        endpoint_url=buckets[0]["s3Endpoint"],
        config=s3_config(),
    )
    client.upload_file(
        apk_path,
        buckets[0]["s3Bucket"],
        key,
        ExtraArgs={"ContentType": "application/vnd.android.package-archive"},
    )
    print(f"已上传到多吉云：{bucket}/{key}")

    github_env = os.environ.get("GITHUB_ENV")
    if not domain:
        print("未配置 DOGECLOUD_DOMAIN，Release 不写 cosUrl。")
        return
    path = f"/{key}"
    url = f"https://{domain}{path}"
    if cdn_key:
        url = f"{url}?auth_key={auth_query(path, cdn_key)}"
    if github_env:
        with open(github_env, "a", encoding="utf-8") as handle:
            handle.write(f"COS_URL<<EOF\n{url}\nEOF\n")
    print("已生成带鉴权的下载地址（不会打印完整链接）。")


if __name__ == "__main__":
    main()
