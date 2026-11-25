"""Convert an actual SEMAS Daegu quarter CSV to the Spring catalog upload format."""

import argparse
import csv
import json
import math
from pathlib import Path


def read_shops(path):
    shops = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        required = {"시도명", "시군구명", "상가업소번호", "상호명", "상권업종소분류명", "경도", "위도"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise ValueError("소진공 상가정보 원본 CSV의 열 이름을 확인하세요.")
        for row in reader:
            if row["시도명"] != "대구광역시" or row["시군구명"] != "중구":
                continue
            shop_key = row["상가업소번호"].strip()
            name = row["상호명"].strip()
            category = row["상권업종소분류명"].strip()
            try:
                longitude = float(row["경도"])
                latitude = float(row["위도"])
            except (TypeError, ValueError):
                continue
            if not shop_key or not name or not category or not (
                math.isfinite(longitude) and math.isfinite(latitude)
                and -180 <= longitude <= 180 and -90 <= latitude <= 90
            ):
                continue
            shop = {
                "shopKey": shop_key,
                "name": name,
                "category": category,
                "buildingKey": (row.get("건물관리번호") or "").strip() or None,
                "longitude": longitude,
                "latitude": latitude,
            }
            if shop_key in shops and shops[shop_key] != shop:
                raise ValueError(f"동일 업소 번호에 서로 다른 레코드가 있습니다: {shop_key}")
            shops[shop_key] = shop
    if not shops:
        raise ValueError("대구 중구에서 좌표와 업종이 확인되는 업소가 없습니다.")
    return list(shops.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        shops = read_shops(args.csv)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    snapshot = {
        "source": "소상공인시장진흥공단 상가(상권)정보 · " + args.csv.name,
        "coverage": "대구 중구",
        "shops": shops,
    }
    args.output.write_text(json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{len(shops)} records in {args.output}")


if __name__ == "__main__":
    main()
