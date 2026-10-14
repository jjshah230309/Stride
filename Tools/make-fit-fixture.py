#!/usr/bin/env python3
"""Writes android/core/src/test/resources/fixtures/fit-tool-ride.fit with `fit-tool`, a FIT writer
that is independent of Stride's own encoder. The Android test suite decodes it to prove the reader
is not merely round-tripping its own output.

    python3 -m venv .venv && .venv/bin/pip install fit-tool
    .venv/bin/python Tools/make-fit-fixture.py
"""
import datetime
import pathlib

from fit_tool.fit_file_builder import FitFileBuilder
from fit_tool.profile.messages.activity_message import ActivityMessage
from fit_tool.profile.messages.device_info_message import DeviceInfoMessage
from fit_tool.profile.messages.event_message import EventMessage
from fit_tool.profile.messages.file_id_message import FileIdMessage
from fit_tool.profile.messages.lap_message import LapMessage
from fit_tool.profile.messages.record_message import RecordMessage
from fit_tool.profile.messages.session_message import SessionMessage
from fit_tool.profile.profile_type import (Event, EventType, FileType, Manufacturer,
                                           Sport, SubSport)

OUT = pathlib.Path(__file__).resolve().parent.parent / "android/core/src/test/resources/fixtures/fit-tool-ride.fit"
START = datetime.datetime(2026, 3, 14, 7, 30, 5, tzinfo=datetime.timezone.utc)


def ms(offset_seconds):
    return int((START.timestamp() + offset_seconds) * 1000)


builder = FitFileBuilder(auto_define=True, min_string_size=50)

file_id = FileIdMessage()
file_id.type = FileType.ACTIVITY
file_id.manufacturer = Manufacturer.GARMIN.value
file_id.product = 3121
file_id.serial_number = 987654
file_id.time_created = ms(0)
builder.add(file_id)

device = DeviceInfoMessage()          # a message type Stride does not read
device.timestamp = ms(0)
device.device_index = 0
device.manufacturer = Manufacturer.GARMIN.value
builder.add(device)

start = EventMessage()                # another one it ignores
start.timestamp = ms(0)
start.event = Event.TIMER
start.event_type = EventType.START
builder.add(start)

N = 120
for i in range(N):
    r = RecordMessage()
    r.timestamp = ms(i)
    r.position_lat = 51.5 + i * 0.00018
    r.position_long = -0.12 + i * 0.00009
    r.altitude = 30.0 + i * 0.1
    r.distance = i * 6.5
    r.speed = 6.5
    r.heart_rate = 130 + (i % 30)
    r.cadence = 85 + (i % 6)
    if i % 3 == 0:                    # power only on some samples
        r.power = 200 + i
    builder.add(r)

lap = LapMessage()
lap.timestamp = ms(N)
lap.start_time = ms(0)
lap.total_elapsed_time = float(N)
lap.total_timer_time = float(N)
lap.total_distance = N * 6.5
builder.add(lap)

session = SessionMessage()
session.timestamp = ms(N)
session.start_time = ms(0)
session.total_elapsed_time = float(N)
session.total_timer_time = float(N)
session.total_distance = N * 6.5
session.total_calories = 64
session.total_ascent = 12
session.sport = Sport.CYCLING
session.sub_sport = SubSport.ROAD
session.avg_heart_rate = 144
session.max_heart_rate = 159
builder.add(session)

activity = ActivityMessage()
activity.timestamp = ms(N)
activity.total_timer_time = float(N)
activity.num_sessions = 1
builder.add(activity)

builder.build().to_file(str(OUT))
print("wrote", OUT, OUT.stat().st_size, "bytes")
