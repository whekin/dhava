#!/usr/bin/env ruby
# Device regression for MapLibre native crashes. No install, restart, log clear,
# or recording mutation: open Nakvali's idle Record tab before running.
# Usage: ruby android/scripts/tab-switch-smoke.rb SERIAL [ROUNDS] [DELAY_SECONDS]
require 'open3'
require 'rexml/document'

serial = ARGV.fetch(0)
rounds = Integer(ARGV.fetch(1, '10'))
delay = Float(ARGV.fetch(2, '0.2'))
abort 'Rounds must be 1..100 and delay 0.05..2 seconds' unless
  (1..100).cover?(rounds) && (0.05..2).cover?(delay)
package = 'com.nakvali.app'
adb = lambda do |*args|
  output, error, status = Open3.capture3('adb', '-s', serial, *args)
  abort "ADB failed: #{error}#{output}" unless status.success?
  output
end
pid = lambda do
  output, = Open3.capture3('adb', '-s', serial, 'shell', 'pidof', package)
  output.strip
end
baseline = pid.call
abort 'Open Nakvali first' if baseline.empty?
check_process = lambda do
  abort "FAIL: PID changed/disappeared (was #{baseline}); inspect logcat -b crash" unless pid.call == baseline
end
focus = adb.call('shell', 'dumpsys', 'window')
abort 'Unlock the device and open Nakvali' unless
  focus.lines.any? { |line| line.include?('mCurrentFocus=') && line.include?("#{package}/") }
adb.call('shell', 'uiautomator', 'dump', '/sdcard/nakvali-tab-smoke.xml')
document = REXML::Document.new(adb.call('shell', 'cat', '/sdcard/nakvali-tab-smoke.xml'))
nodes = REXML::XPath.match(document, '//node')
abort 'Wrong app in UI dump' unless nodes.any? { |node| node.attributes['package'] == package }
coordinates = {}
%w[Record Segments].each do |label|
  candidates = nodes.select do |node|
    (node.attributes['clickable'] == 'true' || node.attributes['selected'] == 'true') &&
      REXML::XPath.match(node, './/node').any? { |child| child.attributes['text'] == label }
  end
  # The bottom navigation item is below any identically named screen control.
  item = candidates.max_by { |node| node.attributes['bounds'].scan(/\d+/).map(&:to_i)[1] }
  abort "Missing #{label} tab; leave recording/save/detail before running" unless item
  x1, y1, x2, y2 = item.attributes['bounds'].scan(/\d+/).map(&:to_i)
  coordinates[label] = [((x1 + x2) / 2).to_s, ((y1 + y2) / 2).to_s]
end
puts "PID=#{baseline} coordinates=#{coordinates} rounds=#{rounds} delay=#{delay}"
rounds.times do |index|
  %w[Segments Record].each do |label|
    check_process.call
    adb.call('shell', 'input', 'tap', *coordinates.fetch(label))
    sleep delay
    check_process.call
  end
  puts "round=#{index + 1} pid=#{baseline}"
end
sleep 1
check_process.call
abort 'Nakvali left foreground during test' unless
  adb.call('shell', 'dumpsys', 'window').lines.any? { |line| line.include?('mCurrentFocus=') && line.include?("#{package}/") }
puts "PASS: #{rounds * 2} tab switches; PID #{baseline} survived (not a lifetime guarantee)"
