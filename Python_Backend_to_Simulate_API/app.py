from flask import Flask, jsonify, abort
import sqlite3

app = Flask(__name__)
DB = "orders.db"

def get_db():
    conn = sqlite3.connect(DB)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    conn.execute("""
        CREATE TABLE IF NOT EXISTS orders (
            order_id TEXT PRIMARY KEY,
            status TEXT NOT NULL
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS order_lines (
            order_id TEXT NOT NULL,
            item_id TEXT NOT NULL,
            description TEXT NOT NULL,
            unit_price REAL NOT NULL
        )
    """)
    # seed one order, only if empty
    existing = conn.execute("SELECT COUNT(*) FROM orders").fetchone()[0]
    if existing == 0:
        conn.execute("INSERT INTO orders VALUES ('1001', 'created')")
        conn.execute("INSERT INTO order_lines VALUES ('1001', 'item-1', 'Blue T-Shirt', 19.99)")
        conn.execute("INSERT INTO order_lines VALUES ('1001', 'item-2', 'Running Shoes', 59.99)")

        conn.execute("INSERT INTO orders VALUES ('1002', 'shipped')")
        conn.execute("INSERT INTO order_lines VALUES ('1002', 'item-3', 'Phone Case', 8.99)")
        conn.execute("INSERT INTO order_lines VALUES ('1002', 'item-4', 'Wireless Charger', 24.99)")

        conn.execute("INSERT INTO orders VALUES ('1003', 'delivered')")
        conn.execute("INSERT INTO order_lines VALUES ('1003', 'item-5', 'Notebook', 4.99)")


    conn.commit()
    conn.close()

@app.route("/orders/<order_id>", methods=["GET"])
def get_order(order_id):
    conn = get_db()
    order = conn.execute("SELECT * FROM orders WHERE order_id = ?", (order_id,)).fetchone()
    if order is None:
        conn.close()
        abort(404)
    lines = conn.execute("SELECT * FROM order_lines WHERE order_id = ?", (order_id,)).fetchall()
    conn.close()
    return jsonify({
        "order_id": order["order_id"],
        "status": order["status"],
        "order_lines": [
            {"item_id": l["item_id"], "description": l["description"], "unit_price": l["unit_price"]}
            for l in lines
        ]
    })

if __name__ == "__main__":
    init_db()
    app.run(port=5001, debug=True)