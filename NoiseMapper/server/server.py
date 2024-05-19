import os
from flask import Flask,request, send_from_directory
import sqlite3
from time import time
app = Flask(__name__)
if not os.path.exists('db'):
    os.makedirs('db')
conn = sqlite3.connect('db/mapdata.db', check_same_thread=False)
conn.row_factory = sqlite3.Row
c = conn.cursor()

# Create a table if it doesn't exist
c.execute('''CREATE TABLE IF NOT EXISTS noise_measurements
             (timestamp INTEGER NOT NULL, room TEXT, noise REAL)''')
conn.commit()

@app.route('/measurements', methods=['POST'])
def save_request():
    # get the data from the request and save to file
    data = request.json
    if data:
        # Check if data is a list
        if isinstance(data, list):
            # Prepare a list to hold the tuples for insertion
            measurements = []
            for measurement in data:
                # Each measurement should be a dictionary with the keys 'timestamp', 'room', and 'noise'
                if  'room' in measurement and 'noise' in measurement:
                    measurements.append((int(time()), measurement['room'], measurement['noise']))
            # Use executemany to insert all the measurements at once
            c.executemany("INSERT INTO noise_measurements (timestamp, room, noise) VALUES (?,?,?)", measurements)
            conn.commit()
            return 'Requests saved successfully'
        else:
            return 'Data provided is not a list'
    else:
            return 'No data provided'

@app.route('/measurements', methods=['GET'])
def get_requests():
    # get the the start and end timestamps from the query parameters
    start_from = request.args.get('start_from')
    end_to = request.args.get('end_to')
    if not start_from or not end_to:
        start_from = str(time() - 3600*24*7)
        end_to = str(time())
    # if the query parameters are malformed, set the default values
    if not start_from.isdigit() or not end_to.isdigit():
        start_from = int(time()) - 3600*24*7
        end_to = int(time())
        
    start_from = int(start_from)
    end_to = int(end_to)
    c.execute("SELECT timestamp, room, noise FROM noise_measurements WHERE timestamp >= ? AND timestamp <= ?", (start_from, end_to))
    return {'data': [dict(row) for row in c.fetchall()]}

@app.route('/resources/<filename>')
def download_file(filename):
    try:
        return send_from_directory('resources', filename)
    except Exception as e:
        return e
if __name__ == '__main__':
    app.run(debug=True,host='0.0.0.0',port=5002)