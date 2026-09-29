#!/usr/bin/env python3
"""
Build default assets based on configuration

This script reads configuration from sdkconfig and builds the appropriate assets.bin
for the current board configuration.

Usage:
    ./build_default_assets.py --sdkconfig <path> --builtin_text_font <font_name> \
        --default_emoji_collection <collection_name> --output <output_path>
"""

import argparse
import hashlib
import io
import os
import shutil
import sys
import json
import struct
from datetime import datetime
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from server.main.shared.wake_word_assets.packer import pack_mmap_assets, pack_sr_models

DYNAMIC_WAKE_LAYOUT_VERSION = 2
WAKE_SLOT_SIZE = 0x300000
WAKE_SLOT_HEADER_SIZE = 0x1000
WAKE_SLOT_MAGIC = b"XZWK"
WAKE_SLOT_HEADER = struct.Struct("<4sIQQ32s")


def assemble_dynamic_wake_word_partition(base_image, wake_image, partition_size, version):
    base_content = Path(base_image).read_bytes()
    wake_content = Path(wake_image).read_bytes()
    slot_a_offset = partition_size - 2 * WAKE_SLOT_SIZE
    if slot_a_offset < 0 or len(base_content) > slot_a_offset:
        raise ValueError("base image crosses wake word slot A")
    if len(wake_content) > WAKE_SLOT_SIZE - WAKE_SLOT_HEADER_SIZE:
        raise ValueError("wake package exceeds slot size")
    output = bytearray(b"\xff" * partition_size)
    output[: len(base_content)] = base_content
    header = WAKE_SLOT_HEADER.pack(
        WAKE_SLOT_MAGIC,
        DYNAMIC_WAKE_LAYOUT_VERSION,
        len(wake_content),
        version,
        hashlib.sha256(wake_content).digest(),
    )
    output[slot_a_offset : slot_a_offset + len(header)] = header
    package_offset = slot_a_offset + WAKE_SLOT_HEADER_SIZE
    output[package_offset : package_offset + len(wake_content)] = wake_content
    return bytes(output)


# =============================================================================
# Build assets functions (from build.py)
# =============================================================================

def ensure_dir(directory):
    """Ensure directory exists, create if not"""
    os.makedirs(directory, exist_ok=True)


def copy_file(src, dst):
    """Copy file"""
    if os.path.exists(src):
        shutil.copy2(src, dst)
        print(f"Copied: {src} -> {dst}")
        return True
    else:
        print(f"Warning: Source file does not exist: {src}")
        return False


def copy_directory(src, dst):
    """Copy directory"""
    if os.path.exists(src):
        shutil.copytree(src, dst, dirs_exist_ok=True)
        print(f"Copied directory: {src} -> {dst}")
        return True
    else:
        print(f"Warning: Source directory does not exist: {src}")
        return False


def process_sr_models(wakenet_model_dirs, multinet_model_dirs, build_dir, assets_dir):
    """Process SR models (wakenet and multinet) and generate srmodels.bin"""
    if not wakenet_model_dirs and not multinet_model_dirs:
        return None
    
    # Create SR models build directory
    sr_models_build_dir = os.path.join(build_dir, "srmodels")
    if os.path.exists(sr_models_build_dir):
        shutil.rmtree(sr_models_build_dir)
    os.makedirs(sr_models_build_dir)
    
    models_processed = 0
    
    # Copy wakenet models if available
    if wakenet_model_dirs:
        for wakenet_model_dir in wakenet_model_dirs:
            wakenet_name = os.path.basename(wakenet_model_dir)
            wakenet_dst = os.path.join(sr_models_build_dir, wakenet_name)
            if copy_directory(wakenet_model_dir, wakenet_dst):
                models_processed += 1
                print(f"Added wakenet model: {wakenet_name}")
    
    # Copy multinet models if available
    if multinet_model_dirs:
        for multinet_model_dir in multinet_model_dirs:
            multinet_name = os.path.basename(multinet_model_dir)
            multinet_dst = os.path.join(sr_models_build_dir, multinet_name)
            if copy_directory(multinet_model_dir, multinet_dst):
                models_processed += 1
                print(f"Added multinet model: {multinet_name}")
    
    if models_processed == 0:
        print("Warning: No SR models were successfully processed")
        return None
    
    # Use the shared packer to generate srmodels.bin
    srmodels_output = os.path.join(sr_models_build_dir, "srmodels.bin")
    try:
        model_dirs = [path for path in Path(sr_models_build_dir).iterdir() if path.is_dir()]
        Path(srmodels_output).write_bytes(pack_sr_models(model_dirs))
        print(f"Generated: {srmodels_output}")
        # Copy srmodels.bin to assets directory
        copy_file(srmodels_output, os.path.join(assets_dir, "srmodels.bin"))
        return "srmodels.bin"
    except Exception as e:
        print(f"Error: Failed to generate srmodels.bin: {e}")
        return None


def process_text_font(text_font_file, assets_dir):
    """Process text_font parameter"""
    if not text_font_file:
        return None
    
    # Copy input file to build/assets directory
    font_filename = os.path.basename(text_font_file)
    font_dst = os.path.join(assets_dir, font_filename)
    if copy_file(text_font_file, font_dst):
        return font_filename
    return None


def process_emoji_collection(emoji_collection_dir, assets_dir):
    """Process emoji_collection parameter"""
    if not emoji_collection_dir:
        return []
    
    emoji_list = []
    
    # Check if this is otto-gif collection
    is_otto_gif = 'otto-emoji-gif-component' in emoji_collection_dir or emoji_collection_dir.endswith('otto-gif')
    
    # Otto GIF emoji aliases mapping
    otto_gif_aliases = {
        "staticstate": ["neutral", "relaxed", "sleepy", "idle"],
        "happy": ["laughing", "funny", "loving", "confident", "winking", "cool", "delicious", "kissy", "silly"],
        "sad": ["crying"],
        "anger": ["angry"],
        "scare": ["surprised", "shocked"],
        "buxue": ["thinking", "confused", "embarrassed"]
    }
    
    # Copy each image from input directory to build/assets directory
    for root, dirs, files in os.walk(emoji_collection_dir):
        for file in files:
            if file.lower().endswith(('.png', '.gif')):
                # Copy file
                src_file = os.path.join(root, file)
                dst_file = os.path.join(assets_dir, file)
                if copy_file(src_file, dst_file):
                    # Get filename without extension
                    filename_without_ext = os.path.splitext(file)[0]
                    
                    # Add main emoji entry
                    emoji_list.append({
                        "name": filename_without_ext,
                        "file": file
                    })
                    
                    # Add aliases for otto-gif emojis
                    if is_otto_gif and filename_without_ext in otto_gif_aliases:
                        for alias in otto_gif_aliases[filename_without_ext]:
                            emoji_list.append({
                                "name": alias,
                                "file": file
                            })
    
    return emoji_list


def process_extra_files(extra_files_dir, assets_dir):
    """Process default_assets_extra_files parameter"""
    if not extra_files_dir:
        return []
    
    if not os.path.exists(extra_files_dir):
        print(f"Warning: Extra files directory not found: {extra_files_dir}")
        return []
    
    extra_files_list = []
    
    # Copy each file from input directory to build/assets directory
    for root, dirs, files in os.walk(extra_files_dir):
        for file in files:
            # Skip hidden files and directories
            if file.startswith('.'):
                continue
                
            # Copy file
            src_file = os.path.join(root, file)
            dst_file = os.path.join(assets_dir, file)
            if copy_file(src_file, dst_file):
                extra_files_list.append(file)
    
    if extra_files_list:
        print(f"Processed {len(extra_files_list)} extra files from: {extra_files_dir}")
    
    return extra_files_list


def generate_index_json(assets_dir, srmodels, text_font, emoji_collection, extra_files=None, multinet_model_info=None):
    """Generate index.json file"""
    index_data = {
        "version": 1
    }
    
    if srmodels:
        index_data["srmodels"] = srmodels
    
    if text_font:
        index_data["text_font"] = text_font
    
    if emoji_collection:
        index_data["emoji_collection"] = emoji_collection
    
    if extra_files:
        index_data["extra_files"] = extra_files
    
    if multinet_model_info:
        index_data["multinet_model"] = multinet_model_info
    
    # Write index.json
    index_path = os.path.join(assets_dir, "index.json")
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(index_data, f, indent=4, ensure_ascii=False)
    
    print(f"Generated: {index_path}")


def generate_config_json(build_dir, assets_dir):
    """Generate config.json file"""
    config_data = {
        "include_path": os.path.join(build_dir, "include"),
        "assets_path": assets_dir,
        "image_file": os.path.join(build_dir, "output", "assets.bin"),
        "lvgl_ver": "9.3.0",
        "assets_size": "0x400000",
        "support_format": ".png, .gif, .jpg, .bin, .json",
        "name_length": "32",
        "split_height": "0",
        "support_qoi": False,
        "support_spng": False,
        "support_sjpg": False,
        "support_sqoi": False,
        "support_raw": False,
        "support_raw_dither": False,
        "support_raw_bgr": False
    }
    
    # Write config.json
    config_path = os.path.join(build_dir, "config.json")
    with open(config_path, 'w', encoding='utf-8') as f:
        json.dump(config_data, f, indent=4, ensure_ascii=False)
    
    print(f"Generated: {config_path}")
    return config_path


def generate_assets_header(target_path, include_path, image_file, assets_path, max_name_len=32):
    """Generate the firmware enum header for an already packed assets image."""
    file_names = sorted(
        (
            path.name
            for path in Path(target_path).iterdir()
            if path.is_file() and path.name != "config.json"
        ),
        key=lambda name: (Path(name).suffix, Path(name).stem),
    )
    total_files, combined_checksum = struct.unpack_from("<II", Path(image_file).read_bytes(), 0)
    if total_files != len(file_names):
        raise ValueError("assets header file count does not match packed image")
    os.makedirs(include_path, exist_ok=True)

    current_year = datetime.now().year
    asset_name = os.path.basename(assets_path)
    header_file_path = os.path.join(include_path, f'mmap_generate_{asset_name}.h')
    with open(header_file_path, 'w', encoding='utf-8') as output_header:
        output_header.write('/*\n')
        output_header.write(' * SPDX-FileCopyrightText: 2022-{} Espressif Systems (Shanghai) CO LTD\n'.format(current_year))
        output_header.write(' *\n')
        output_header.write(' * SPDX-License-Identifier: Apache-2.0\n')
        output_header.write(' */\n\n')
        output_header.write('/**\n')
        output_header.write(' * @file\n')
        output_header.write(" * @brief This file was generated by esp_mmap_assets, don't modify it\n")
        output_header.write(' */\n\n')
        output_header.write('#pragma once\n\n')
        output_header.write("#include \"esp_mmap_assets.h\"\n\n")
        output_header.write(f'#define MMAP_{asset_name.upper()}_FILES           {total_files}\n')
        output_header.write(f'#define MMAP_{asset_name.upper()}_CHECKSUM        0x{combined_checksum:04X}\n\n')
        output_header.write(f'enum MMAP_{asset_name.upper()}_LISTS {{\n')

        for i, file_name in enumerate(file_names):
            if len(file_name.encode("utf-8")) > max_name_len:
                raise ValueError(f'asset name exceeds {max_name_len} bytes: {file_name}')
            enum_name = file_name.replace('.', '_')
            output_header.write(f'    MMAP_{asset_name.upper()}_{enum_name.upper()} = {i},        /*!< {file_name} */\n')

        output_header.write('};\n')

    print(f'Generated asset header: {header_file_path}')


# =============================================================================
# Configuration and main functions
# =============================================================================

def read_wakenet_from_sdkconfig(sdkconfig_path):
    """
    Read wakenet models from sdkconfig (based on movemodel.py logic)
    Returns a list of wakenet model names
    """
    if not os.path.exists(sdkconfig_path):
        print(f"Warning: sdkconfig file not found: {sdkconfig_path}")
        return []
        
    models = []
    with io.open(sdkconfig_path, "r", encoding="utf-8") as f:
        for label in f:
            label = label.strip("\n")
            if 'CONFIG_SR_WN' in label and '#' not in label[0]:
                if '_NONE' in label:
                    continue
                if '=' in label:
                    label = label.split("=")[0]
                if '_MULTI' in label:
                    label = label[:-6]
                model_name = label.split("_SR_WN_")[-1].lower()
                models.append(model_name)

    return models


def read_multinet_from_sdkconfig(sdkconfig_path):
    """
    Read multinet models from sdkconfig (based on movemodel.py logic)
    Returns a list of multinet model names
    """
    if not os.path.exists(sdkconfig_path):
        print(f"Warning: sdkconfig file not found: {sdkconfig_path}")
        return []
        
    with io.open(sdkconfig_path, "r", encoding="utf-8") as f:
        models_string = ''
        for label in f:
            label = label.strip("\n")
            if 'CONFIG_SR_MN' in label and label[0] != '#':
                models_string += label

    models = []
    if "CONFIG_SR_MN_CN_MULTINET3_SINGLE_RECOGNITION" in models_string:
        models.append('mn3_cn')
    elif "CONFIG_SR_MN_CN_MULTINET4_5_SINGLE_RECOGNITION_QUANT8" in models_string:
        models.append('mn4q8_cn')
    elif "CONFIG_SR_MN_CN_MULTINET4_5_SINGLE_RECOGNITION" in models_string:
        models.append('mn4_cn')
    elif "CONFIG_SR_MN_CN_MULTINET5_RECOGNITION_QUANT8" in models_string:
        models.append('mn5q8_cn')
    elif "CONFIG_SR_MN_CN_MULTINET6_QUANT" in models_string:
        models.append('mn6_cn')
    elif "CONFIG_SR_MN_CN_MULTINET6_AC_QUANT" in models_string:
        models.append('mn6_cn_ac')
    elif "CONFIG_SR_MN_CN_MULTINET7_QUANT" in models_string:
        models.append('mn7_cn')
    elif "CONFIG_SR_MN_CN_MULTINET7_AC_QUANT" in models_string:
        models.append('mn7_cn_ac')

    if "CONFIG_SR_MN_EN_MULTINET5_SINGLE_RECOGNITION_QUANT8" in models_string:
        models.append('mn5q8_en')
    elif "CONFIG_SR_MN_EN_MULTINET5_SINGLE_RECOGNITION" in models_string:
        models.append('mn5_en')
    elif "CONFIG_SR_MN_EN_MULTINET6_QUANT" in models_string:
        models.append('mn6_en')
    elif "CONFIG_SR_MN_EN_MULTINET7_QUANT" in models_string:
        models.append('mn7_en')

    if "MULTINET6" in models_string or "MULTINET7" in models_string:
        models.append('fst')

    return models


def read_wake_word_type_from_sdkconfig(sdkconfig_path):
    """
    Read wake word type configuration from sdkconfig
    Returns a dict with wake word type info
    """
    if not os.path.exists(sdkconfig_path):
        print(f"Warning: sdkconfig file not found: {sdkconfig_path}")
        return {
            'use_esp_wake_word': False,
            'use_afe_wake_word': False,
            'use_custom_wake_word': False,
            'wake_word_disabled': True
        }
        
    config_values = {
        'use_esp_wake_word': False,
        'use_afe_wake_word': False,
        'use_custom_wake_word': False,
        'wake_word_disabled': False
    }
    
    with io.open(sdkconfig_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip("\n")
            if line.startswith('#'):
                continue
                
            # Check for wake word type configuration
            if 'CONFIG_USE_ESP_WAKE_WORD=y' in line:
                config_values['use_esp_wake_word'] = True
            elif 'CONFIG_USE_AFE_WAKE_WORD=y' in line:
                config_values['use_afe_wake_word'] = True
            elif 'CONFIG_USE_CUSTOM_WAKE_WORD=y' in line:
                config_values['use_custom_wake_word'] = True
            elif 'CONFIG_WAKE_WORD_DISABLED=y' in line:
                config_values['wake_word_disabled'] = True
    
    return config_values


def read_custom_wake_word_from_sdkconfig(sdkconfig_path):
    """
    Read custom wake word configuration from sdkconfig
    Returns a dict with custom wake word info or None if not configured
    """
    if not os.path.exists(sdkconfig_path):
        print(f"Warning: sdkconfig file not found: {sdkconfig_path}")
        return None
        
    config_values = {}
    with io.open(sdkconfig_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip("\n")
            if line.startswith('#') or '=' not in line:
                continue
                
            # Check for custom wake word configuration
            if 'CONFIG_USE_CUSTOM_WAKE_WORD=y' in line:
                config_values['use_custom_wake_word'] = True
            elif 'CONFIG_CUSTOM_WAKE_WORD=' in line and not line.startswith('#'):
                # Extract string value (remove quotes)
                value = line.split('=', 1)[1].strip('"')
                config_values['wake_word'] = value
            elif 'CONFIG_CUSTOM_WAKE_WORD_DISPLAY=' in line and not line.startswith('#'):
                # Extract string value (remove quotes)
                value = line.split('=', 1)[1].strip('"')
                config_values['display'] = value
            elif 'CONFIG_CUSTOM_WAKE_WORD_THRESHOLD=' in line and not line.startswith('#'):
                # Extract numeric value
                value = line.split('=', 1)[1]
                try:
                    config_values['threshold'] = int(value)
                except ValueError:
                    try:
                        config_values['threshold'] = float(value)
                    except ValueError:
                        print(f"Warning: Invalid threshold value: {value}")
                        config_values['threshold'] = 20  # default (will be converted to 0.2)
    
    # Return config only if custom wake word is enabled and required fields are present
    if (config_values.get('use_custom_wake_word', False) and 
        'wake_word' in config_values and 
        'display' in config_values and 
        'threshold' in config_values):
        return {
            'wake_word': config_values['wake_word'],
            'display': config_values['display'],
            'threshold': config_values['threshold'] / 100.0  # Convert to decimal (20 -> 0.2)
        }
    
    return None


def get_language_from_multinet_models(multinet_models):
    """
    Determine language from multinet model names
    Returns 'cn', 'en', or None
    """
    if not multinet_models:
        return None
    
    # Check for Chinese models
    cn_indicators = ['_cn', 'cn_']
    en_indicators = ['_en', 'en_']
    
    has_cn = any(any(indicator in model for indicator in cn_indicators) for model in multinet_models)
    has_en = any(any(indicator in model for indicator in en_indicators) for model in multinet_models)
    
    # If both or neither, default to cn
    if has_cn and not has_en:
        return 'cn'
    elif has_en and not has_cn:
        return 'en'
    else:
        return 'cn'  # Default to Chinese


def get_wakenet_model_paths(model_names, esp_sr_model_path):
    """
    Get the full paths to the wakenet model directories
    Returns a list of valid model paths
    """
    if not model_names:
        return []
    
    valid_paths = []
    for model_name in model_names:
        wakenet_model_path = os.path.join(esp_sr_model_path, 'wakenet_model', model_name)
        if os.path.exists(wakenet_model_path):
            valid_paths.append(wakenet_model_path)
        else:
            print(f"Warning: Wakenet model directory not found: {wakenet_model_path}")
    
    return valid_paths


def get_multinet_model_paths(model_names, esp_sr_model_path):
    """
    Get the full paths to the multinet model directories
    Returns a list of valid model paths
    """
    if not model_names:
        return []
    
    valid_paths = []
    for model_name in model_names:
        multinet_model_path = os.path.join(esp_sr_model_path, 'multinet_model', model_name)
        if os.path.exists(multinet_model_path):
            valid_paths.append(multinet_model_path)
        else:
            print(f"Warning: Multinet model directory not found: {multinet_model_path}")
    
    return valid_paths


def get_text_font_path(builtin_text_font, xiaozhi_fonts_path):
    """
    Get the text font path if needed
    Returns the font file path or None if no font is needed
    """
    if not builtin_text_font or 'basic' not in builtin_text_font:
        return None
    
    # Convert from basic to common font name
    # e.g., font_puhui_basic_16_4 -> font_puhui_common_16_4.bin
    if builtin_text_font.startswith('font_noto_'):
        font_name = builtin_text_font.replace('basic', 'qwen') + '.bin'
    else:
        font_name = builtin_text_font.replace('basic', 'common') + '.bin'
    font_path = os.path.join(xiaozhi_fonts_path, 'cbin', font_name)
    
    if os.path.exists(font_path):
        return font_path
    else:
        print(f"Warning: Font file not found: {font_path}")
        return None


def get_emoji_collection_path(default_emoji_collection, xiaozhi_fonts_path, project_root=None):
    """
    Get the emoji collection path if needed
    Returns the emoji directory path or None if no emoji collection is needed

    Supports:
    - Project-local collections under <project_root>/resources/emoji/<name> (e.g., fluent_3d)
    - PNG emoji collections from xiaozhi-fonts (e.g., emojis_32, twemoji_64)
    - GIF emoji collections from xiaozhi-fonts (e.g., noto-emoji_128, noto-emoji_64)
    - Otto GIF emoji collection (otto-gif)
    """
    if not default_emoji_collection:
        return None

    # Project-local collections take precedence (committed under firmware/resources/emoji/)
    if project_root:
        local_emoji_path = os.path.join(project_root, 'resources', 'emoji',
                                        default_emoji_collection)
        if os.path.exists(local_emoji_path):
            return local_emoji_path

    # Special handling for otto-gif collection
    if default_emoji_collection == 'otto-gif':
        if project_root:
            otto_gif_path = os.path.join(project_root, 'managed_components',
                                        'txp666__otto-emoji-gif-component', 'gifs')
            if os.path.exists(otto_gif_path):
                return otto_gif_path
            else:
                print(f"Warning: Otto GIF emoji collection directory not found: {otto_gif_path}")
                return None
        else:
            print("Warning: project_root not provided, cannot locate otto-gif collection")
            return None
    
    # Try PNG emoji collections first (e.g., emojis_32, twemoji_64)
    emoji_path = os.path.join(xiaozhi_fonts_path, 'png', default_emoji_collection)
    if os.path.exists(emoji_path):
        return emoji_path
    
    # Try GIF emoji collections (e.g., noto-emoji_128, noto-emoji_64, noto-emoji_32)
    emoji_path = os.path.join(xiaozhi_fonts_path, 'gif', default_emoji_collection)
    if os.path.exists(emoji_path):
        return emoji_path
    
    print(f"Warning: Emoji collection directory not found in png/ or gif/: {default_emoji_collection}")
    return None


def get_board_default_emoji_collection(board_name, project_root):
    """
    Read the default emoji collection declared by a board config.json
    (builds[].assets.default_emoji_collection). Returns None when the board
    or the declaration is missing.
    """
    if not board_name or not project_root:
        return None
    board_config_path = os.path.join(project_root, 'main', 'boards', board_name, 'config.json')
    if not os.path.exists(board_config_path):
        print(f"Warning: Board config not found: {board_config_path}")
        return None
    try:
        with io.open(board_config_path, "r", encoding="utf-8") as f:
            board_config = json.load(f)
    except (OSError, ValueError) as e:
        print(f"Warning: Failed to read board config {board_config_path}: {e}")
        return None
    for build in board_config.get('builds', []):
        collection = build.get('assets', {}).get('default_emoji_collection')
        if collection:
            return collection
    return None


def build_assets_integrated(wakenet_model_paths, multinet_model_paths, text_font_path, emoji_collection_path, extra_files_path, output_path, multinet_model_info=None):
    """
    Build assets using integrated functions (no external dependencies)
    """
    # Create temporary build directory
    temp_build_dir = os.path.join(os.path.dirname(output_path), "temp_build")
    assets_dir = os.path.join(temp_build_dir, "assets")
    
    try:
        # Clean and create directories
        if os.path.exists(temp_build_dir):
            shutil.rmtree(temp_build_dir)
        ensure_dir(temp_build_dir)
        ensure_dir(assets_dir)
        
        print("Starting to build assets...")
        
        # Process each component
        srmodels = process_sr_models(wakenet_model_paths, multinet_model_paths, temp_build_dir, assets_dir) if (wakenet_model_paths or multinet_model_paths) else None
        text_font = process_text_font(text_font_path, assets_dir) if text_font_path else None
        emoji_collection = process_emoji_collection(emoji_collection_path, assets_dir) if emoji_collection_path else None
        extra_files = process_extra_files(extra_files_path, assets_dir) if extra_files_path else None
        
        # Generate index.json
        generate_index_json(assets_dir, srmodels, text_font, emoji_collection, extra_files, multinet_model_info)
        
        # Generate config.json for packing
        config_path = generate_config_json(temp_build_dir, assets_dir)
        
        # Load config and pack assets
        with open(config_path, 'r', encoding='utf-8') as f:
            config_data = json.load(f)
        
        # Use the shared packer. Header generation remains local for firmware includes.
        include_path = config_data['include_path']
        image_file = config_data['image_file']
        pack_mmap_assets(
            {
                path.name: path.read_bytes()
                for path in Path(assets_dir).iterdir()
                if path.is_file() and path.name != "config.json"
            },
            Path(image_file),
        )
        generate_assets_header(
            assets_dir,
            include_path,
            image_file,
            "assets",
            int(config_data['name_length']),
        )
        
        # Copy final assets.bin to output location
        if os.path.exists(image_file):
            shutil.copy2(image_file, output_path)
            print(f"Successfully generated assets.bin: {output_path}")
            
            # Show size information
            total_size = os.path.getsize(output_path)
            print(f"Assets file size: {total_size / 1024:.2f}K ({total_size} bytes)")
            
            return True
        else:
            print(f"Error: Generated assets.bin not found: {image_file}")
            return False
            
    except Exception as e:
        print(f"Error: Failed to build assets: {e}")
        return False
    finally:
        # Clean up temporary directory
        if os.path.exists(temp_build_dir):
            shutil.rmtree(temp_build_dir)


def build_dynamic_wake_word_assets(multinet_model_paths, multinet_model_info, output_path, display_word):
    if not multinet_model_paths or not multinet_model_info:
        raise ValueError("dynamic wake word layout requires a Multinet model")
    metadata = {
        "schema": 1,
        "version": 1,
        "word": display_word,
        "chip": "esp32s3",
        "model": Path(multinet_model_paths[0]).name,
    }
    index = {
        "version": 1,
        "srmodels": "srmodels.bin",
        "wake_word_bundle": metadata,
        "multinet_model": multinet_model_info,
    }
    pack_mmap_assets(
        {
            "index.json": json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            "wake_word.json": json.dumps(metadata, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            "srmodels.bin": pack_sr_models([Path(path) for path in multinet_model_paths]),
        },
        Path(output_path),
    )


def main():
    parser = argparse.ArgumentParser(description='Build default assets based on configuration')
    parser.add_argument('--sdkconfig', required=True, help='Path to sdkconfig file')
    parser.add_argument('--builtin_text_font', help='Builtin text font name (e.g., font_puhui_basic_16_4)')
    parser.add_argument('--board', help='Board name; reads builds[].assets.default_emoji_collection '
                                        'from main/boards/<board>/config.json as the emoji collection default')
    parser.add_argument('--emoji_collection', help='Default emoji collection name (e.g., fluent_3d, emojis_32); '
                                                   'overrides the board config declaration')
    parser.add_argument('--output', required=True, help='Output path for assets.bin')
    parser.add_argument('--esp_sr_model_path', help='Path to ESP-SR model directory')
    parser.add_argument('--xiaozhi_fonts_path', help='Path to xiaozhi-fonts component directory')
    parser.add_argument('--extra_files', help='Path to extra files directory to be included in assets')
    parser.add_argument('--dynamic-wake-word-layout', action='store_true')
    parser.add_argument('--assets-partition-size', type=lambda value: int(value, 0))
    parser.add_argument('--default-wake-word')
    
    args = parser.parse_args()
    
    # Set default paths if not provided
    if not args.esp_sr_model_path or not args.xiaozhi_fonts_path:
        # Calculate project root from script location
        script_dir = os.path.dirname(os.path.abspath(__file__))
        project_root = os.path.dirname(script_dir)

        if not args.esp_sr_model_path:
            args.esp_sr_model_path = os.path.join(project_root, "managed_components", "espressif__esp-sr", "model")

        if not args.xiaozhi_fonts_path:
            # Upstream layout keeps the component at components/xiaozhi-fonts; this
            # repository resolves it from the IDF component manager directory.
            candidates = [
                os.path.join(project_root, "components", "xiaozhi-fonts"),
                os.path.join(project_root, "managed_components", "78__xiaozhi-fonts"),
            ]
            args.xiaozhi_fonts_path = next(
                (path for path in candidates if os.path.exists(path)), candidates[0]
            )

    # Board config declaration is the default; explicit --emoji_collection wins
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    if not args.emoji_collection and args.board:
        args.emoji_collection = get_board_default_emoji_collection(args.board, project_root)
    
    print("Building default assets...")
    print(f"  sdkconfig: {args.sdkconfig}")
    print(f"  builtin_text_font: {args.builtin_text_font}")
    print(f"  emoji_collection: {args.emoji_collection}")
    print(f"  output: {args.output}")
    
    # Read wake word type configuration from sdkconfig
    wake_word_config = read_wake_word_type_from_sdkconfig(args.sdkconfig)
    
    # Read SR models from sdkconfig
    wakenet_model_names = read_wakenet_from_sdkconfig(args.sdkconfig)
    multinet_model_names = read_multinet_from_sdkconfig(args.sdkconfig)
    
    # Apply wake word logic to decide which models to package
    wakenet_model_paths = []
    multinet_model_paths = []
    
    # 1. Only package wakenet models if USE_ESP_WAKE_WORD=y or USE_AFE_WAKE_WORD=y
    if wake_word_config['use_esp_wake_word'] or wake_word_config['use_afe_wake_word']:
        wakenet_model_paths = get_wakenet_model_paths(wakenet_model_names, args.esp_sr_model_path)
    elif wakenet_model_names:
        print(f"  Note: Found wakenet models {wakenet_model_names} but wake word type is not ESP/AFE, skipping")
    
    # 2. Error check: if USE_CUSTOM_WAKE_WORD=y but no multinet models selected, report error
    if wake_word_config['use_custom_wake_word'] and not multinet_model_names:
        print("Error: USE_CUSTOM_WAKE_WORD is enabled but no multinet models are selected in sdkconfig")
        print("Please select appropriate CONFIG_SR_MN_* options in menuconfig, or disable USE_CUSTOM_WAKE_WORD")
        sys.exit(1)
    
    # 3. Only package multinet models if USE_CUSTOM_WAKE_WORD=y
    if wake_word_config['use_custom_wake_word']:
        multinet_model_paths = get_multinet_model_paths(multinet_model_names, args.esp_sr_model_path)
    elif multinet_model_names:
        print(f"  Note: Found multinet models {multinet_model_names} but USE_CUSTOM_WAKE_WORD is disabled, skipping")
    
    # Print model information (only for models that will actually be packaged)
    if wakenet_model_paths:
        print(f"  wakenet models: {', '.join(wakenet_model_names)} (will be packaged)")
    if multinet_model_paths:
        print(f"  multinet models: {', '.join(multinet_model_names)} (will be packaged)")
    
    # Get text font path if needed
    text_font_path = get_text_font_path(args.builtin_text_font, args.xiaozhi_fonts_path)
    
    # Get emoji collection path if needed
    # Calculate project root from script location for otto-gif support
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    emoji_collection_path = get_emoji_collection_path(args.emoji_collection, args.xiaozhi_fonts_path, project_root)
    
    # Get extra files path if provided
    extra_files_path = args.extra_files
    
    # Read custom wake word configuration
    custom_wake_word_config = read_custom_wake_word_from_sdkconfig(args.sdkconfig)
    multinet_model_info = None
    
    if custom_wake_word_config and multinet_model_paths:
        # Determine language from multinet models
        language = get_language_from_multinet_models(multinet_model_names)
        
        # Build multinet_model info structure
        multinet_model_info = {
            "language": language,
            "duration": 3000,  # Default duration in ms
            "threshold": custom_wake_word_config['threshold'],
            "commands": [
                {
                    "command": custom_wake_word_config['wake_word'],
                    "text": custom_wake_word_config['display'],
                    "action": "wake"
                }
            ]
        }
        print(f"  custom wake word: {custom_wake_word_config['wake_word']} ({custom_wake_word_config['display']})")
        print(f"  wake word language: {language}")
        print(f"  wake word threshold: {custom_wake_word_config['threshold']}")
    
    # Check if we have anything to build
    if not wakenet_model_paths and not multinet_model_paths and not text_font_path and not emoji_collection_path and not extra_files_path and not multinet_model_info:
        print("Warning: No assets to build (no SR models, text font, emoji collection, extra files, or custom wake word)")
        # Create an empty assets.bin file
        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        with open(args.output, 'wb') as f:
            pass  # Create empty file
        print(f"Created empty assets.bin: {args.output}")
        return
    
    if args.dynamic_wake_word_layout:
        if not args.assets_partition_size or not args.default_wake_word:
            print("Error: dynamic wake word layout requires partition size and default wake word")
            sys.exit(1)
        base_output = Path(args.output).with_suffix(".base.bin")
        wake_output = Path(args.output).with_suffix(".wake.bin")
        success = build_assets_integrated(
            [], [], text_font_path, emoji_collection_path, extra_files_path,
            str(base_output), None,
        )
        if success:
            try:
                build_dynamic_wake_word_assets(
                    multinet_model_paths, multinet_model_info, wake_output,
                    args.default_wake_word,
                )
                Path(args.output).write_bytes(assemble_dynamic_wake_word_partition(
                    base_output, wake_output, args.assets_partition_size, 1,
                ))
            finally:
                base_output.unlink(missing_ok=True)
                wake_output.unlink(missing_ok=True)
    else:
        success = build_assets_integrated(
            wakenet_model_paths, multinet_model_paths, text_font_path, emoji_collection_path,
            extra_files_path, args.output, multinet_model_info,
        )
    
    if not success:
        sys.exit(1)
    
    print("Build completed successfully!")


if __name__ == "__main__":
    main()
