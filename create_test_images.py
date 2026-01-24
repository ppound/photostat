#!/usr/bin/env python3
"""Create test images with EXIF metadata for testing PhotoVault."""

import os
import subprocess
import random
from datetime import datetime, timedelta
from PIL import Image, ImageDraw, ImageFont

# Test image configurations
TEST_IMAGES = [
    {
        "filename": "canon_landscape_001.jpg",
        "width": 1920, "height": 1080,
        "color": (34, 139, 34),  # Forest green
        "text": "Landscape Shot",
        "exif": {
            "Make": "Canon",
            "Model": "Canon EOS R5",
            "LensModel": "RF 24-70mm F2.8 L IS USM",
            "FocalLength": "35",
            "FNumber": "8.0",
            "ExposureTime": "1/250",
            "ISO": "100",
            "DateTimeOriginal": "2024:06:15 14:30:00",
            "Artist": "John Photographer",
            "Copyright": "2024 John Photographer",
        }
    },
    {
        "filename": "canon_portrait_002.jpg",
        "width": 1080, "height": 1620,
        "color": (255, 218, 185),  # Peach
        "text": "Portrait Shot",
        "exif": {
            "Make": "Canon",
            "Model": "Canon EOS R5",
            "LensModel": "RF 85mm F1.2 L USM",
            "FocalLength": "85",
            "FNumber": "1.4",
            "ExposureTime": "1/500",
            "ISO": "200",
            "DateTimeOriginal": "2024:07:20 10:15:00",
            "Artist": "John Photographer",
        }
    },
    {
        "filename": "nikon_wildlife_003.jpg",
        "width": 1920, "height": 1280,
        "color": (139, 90, 43),  # Brown
        "text": "Wildlife Shot",
        "exif": {
            "Make": "Nikon",
            "Model": "Nikon Z9",
            "LensModel": "NIKKOR Z 100-400mm f/4.5-5.6 VR S",
            "FocalLength": "400",
            "FNumber": "5.6",
            "ExposureTime": "1/2000",
            "ISO": "1600",
            "DateTimeOriginal": "2024:03:10 07:45:00",
        }
    },
    {
        "filename": "nikon_street_004.jpg",
        "width": 1620, "height": 1080,
        "color": (128, 128, 128),  # Gray
        "text": "Street Photography",
        "exif": {
            "Make": "Nikon",
            "Model": "Nikon Z6 III",
            "LensModel": "NIKKOR Z 35mm f/1.8 S",
            "FocalLength": "35",
            "FNumber": "2.8",
            "ExposureTime": "1/125",
            "ISO": "800",
            "DateTimeOriginal": "2024:09:05 18:30:00",
        }
    },
    {
        "filename": "sony_astro_005.jpg",
        "width": 1920, "height": 1080,
        "color": (25, 25, 112),  # Midnight blue
        "text": "Astrophotography",
        "exif": {
            "Make": "Sony",
            "Model": "Sony A7R V",
            "LensModel": "Sony FE 14mm F1.8 GM",
            "FocalLength": "14",
            "FNumber": "1.8",
            "ExposureTime": "20",
            "ISO": "6400",
            "DateTimeOriginal": "2024:08:12 23:45:00",
            "GPSLatitude": "36.0544",
            "GPSLatitudeRef": "N",
            "GPSLongitude": "112.1401",
            "GPSLongitudeRef": "W",
        }
    },
    {
        "filename": "sony_macro_006.jpg",
        "width": 1440, "height": 1080,
        "color": (50, 205, 50),  # Lime green
        "text": "Macro Shot",
        "exif": {
            "Make": "Sony",
            "Model": "Sony A7R V",
            "LensModel": "Sony FE 90mm F2.8 Macro G OSS",
            "FocalLength": "90",
            "FNumber": "11",
            "ExposureTime": "1/60",
            "ISO": "400",
            "DateTimeOriginal": "2024:05:22 11:20:00",
        }
    },
    {
        "filename": "fuji_travel_007.jpg",
        "width": 1920, "height": 1280,
        "color": (135, 206, 235),  # Sky blue
        "text": "Travel Photo",
        "exif": {
            "Make": "Fujifilm",
            "Model": "Fujifilm X-T5",
            "LensModel": "XF 16-55mm F2.8 R LM WR",
            "FocalLength": "23",
            "FNumber": "5.6",
            "ExposureTime": "1/500",
            "ISO": "160",
            "DateTimeOriginal": "2024:04:18 16:00:00",
            "GPSLatitude": "48.8566",
            "GPSLatitudeRef": "N",
            "GPSLongitude": "2.3522",
            "GPSLongitudeRef": "E",
        }
    },
    {
        "filename": "fuji_street_008.jpg",
        "width": 1080, "height": 1080,
        "color": (255, 165, 0),  # Orange
        "text": "Square Format",
        "exif": {
            "Make": "Fujifilm",
            "Model": "Fujifilm X100VI",
            "LensModel": "Fujinon 23mm F2",
            "FocalLength": "23",
            "FNumber": "4.0",
            "ExposureTime": "1/250",
            "ISO": "320",
            "DateTimeOriginal": "2024:10:01 09:30:00",
        }
    },
    {
        "filename": "panasonic_video_009.jpg",
        "width": 1920, "height": 1080,
        "color": (220, 20, 60),  # Crimson
        "text": "Video Still",
        "exif": {
            "Make": "Panasonic",
            "Model": "Panasonic S5 II",
            "LensModel": "Lumix S 24-105mm F4 Macro OIS",
            "FocalLength": "50",
            "FNumber": "4.0",
            "ExposureTime": "1/50",
            "ISO": "3200",
            "DateTimeOriginal": "2024:11:15 20:00:00",
        }
    },
    {
        "filename": "leica_bw_010.jpg",
        "width": 1620, "height": 1080,
        "color": (64, 64, 64),  # Dark gray
        "text": "B&W Street",
        "exif": {
            "Make": "Leica",
            "Model": "Leica Q3",
            "LensModel": "Summilux 28mm f/1.7 ASPH",
            "FocalLength": "28",
            "FNumber": "2.8",
            "ExposureTime": "1/1000",
            "ISO": "100",
            "DateTimeOriginal": "2024:02:28 15:45:00",
            "Artist": "Street Master",
            "Copyright": "2024 Street Master Photography",
        }
    },
]

def create_test_image(config, output_dir):
    """Create a test image with the specified configuration."""
    filepath = os.path.join(output_dir, config["filename"])

    # Create image with solid color
    img = Image.new('RGB', (config["width"], config["height"]), config["color"])
    draw = ImageDraw.Draw(img)

    # Add text
    text = config["text"]
    # Try to use a larger font, fall back to default
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 48)
    except:
        font = ImageFont.load_default()

    # Center the text
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = bbox[2] - bbox[0]
    text_height = bbox[3] - bbox[1]
    x = (config["width"] - text_width) // 2
    y = (config["height"] - text_height) // 2

    # Draw text with shadow
    draw.text((x+2, y+2), text, fill=(0, 0, 0), font=font)
    draw.text((x, y), text, fill=(255, 255, 255), font=font)

    # Add metadata text
    meta_text = f"{config['exif'].get('Make', '')} {config['exif'].get('Model', '')}"
    meta_text2 = f"{config['exif'].get('FocalLength', '')}mm f/{config['exif'].get('FNumber', '')} ISO {config['exif'].get('ISO', '')}"

    try:
        small_font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 24)
    except:
        small_font = font

    draw.text((20, config["height"] - 70), meta_text, fill=(255, 255, 255), font=small_font)
    draw.text((20, config["height"] - 40), meta_text2, fill=(255, 255, 255), font=small_font)

    # Save image
    img.save(filepath, "JPEG", quality=95)
    print(f"Created: {filepath}")

    return filepath

def add_exif_metadata(filepath, exif_data):
    """Add EXIF metadata using exiftool."""
    args = ["exiftool", "-overwrite_original"]

    for key, value in exif_data.items():
        args.append(f"-{key}={value}")

    args.append(filepath)

    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Warning: exiftool error for {filepath}: {result.stderr}")
    else:
        print(f"Added EXIF to: {filepath}")

def main():
    output_dir = "/home/ppound/claude-code/photovault/test_images"
    os.makedirs(output_dir, exist_ok=True)

    print(f"Creating {len(TEST_IMAGES)} test images in {output_dir}\n")

    for config in TEST_IMAGES:
        filepath = create_test_image(config, output_dir)
        add_exif_metadata(filepath, config["exif"])
        print()

    print(f"\nDone! Created {len(TEST_IMAGES)} test images with EXIF metadata.")
    print(f"Directory: {output_dir}")

if __name__ == "__main__":
    main()
